package io.nexum.order;

import io.nexum.message.FixTags;
import io.nexum.routing.OrderFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An order that decides for itself what happens to it.
 *
 * <p>Events go in, events come out, and the state lives here. A caller cannot
 * set the state — it offers what occurred and receives what the order concluded,
 * which is the only way one rule about, say, a fill arriving during a cancel
 * ends up applied consistently everywhere.
 *
 * <p>Mutable on purpose. The order is the thing being tracked, and the events it
 * returns are the immutable record of how it got here; making the order itself a
 * value would mean every caller threading a replacement through, and one of them
 * eventually dropping it.
 *
 * <p>Nothing here performs IO. Journalling, forwarding and raising are the
 * caller's, which is what lets the whole lifecycle be tested against a fixed
 * clock and no transport.
 *
 * <p>Thread safety: one order is touched by more than one session thread as a
 * matter of course — a cancel arrives from the client while a fill arrives from
 * the venue, each on its own connection. Every event is applied under this
 * order's own monitor, so the two are serialised against each other without
 * putting different orders in contention.
 */
public final class ManagedOrder {


    private final String orderId;
    private final String sessionId;
    private final String clientId;
    /**
     * What the client is told about this order.
     *
     * <p>Its ClOrdID advances when an amendment is accepted: FIX 4.4 has a
     * report name the most recent one, so a client tracking its order by the id
     * it last sent would otherwise see fills for an id it has already replaced.
     * The order's own identity — day, session, and the ClOrdID it arrived with
     * — does not move with it; that is what every index in the cache leads back
     * to, and an amendment is a later event about the same order.
     */
    private OrderView clientView;

    private String destinationId;
    private OrderView destinationView;
    private OrderState state;
    private double cumQty;
    private PendingRequest pending;
    private long lastEventAt;
    private int reportCount;
    private String venueOrderId;

    private ManagedOrder(
            String orderId,
            String sessionId,
            String clientId,
            OrderView clientView,
            long at) {
        this.orderId = orderId;
        this.sessionId = sessionId;
        this.clientId = clientId;
        this.clientView = clientView;
        this.state = OrderState.CREATED;
        this.lastEventAt = at;
    }

    /** Open an order from a client's request. It exists; nothing has been sent. */
    public static ManagedOrder accept(String orderId, InboundEvent.ClientOrder request) {
        return new ManagedOrder(
                orderId,
                request.sessionId(),
                request.clientId(),
                OrderView.of(request.clientClOrdId(), request.fields()),
                request.at());
    }

    /** Rebuild from a journal, where the state is already known. */
    public static ManagedOrder restore(Order stored, long at) {
        ManagedOrder order = new ManagedOrder(
                stored.orderId(), stored.sessionId(), stored.clientId(), stored.client(), at);
        order.destinationId = stored.destinationId();
        order.destinationView = stored.destination();
        order.state = stored.state();
        order.cumQty = stored.cumQty();
        order.pending = stored.pending();
        order.venueOrderId = stored.destination().orderId();
        return order;
    }

    // ------------------------------------------------------------------
    // The one way in
    // ------------------------------------------------------------------

    /**
     * Offer an event and receive what the order made of it.
     *
     * @return what changed, in order; empty when the event meant nothing at all
     */
    public synchronized List<OutboundEvent> on(InboundEvent event) {
        List<OutboundEvent> out = new ArrayList<>();
        lastEventAt = event.at();

        switch (event) {
            case InboundEvent.ClientOrder ignored ->
                    out.add(new OutboundEvent.Disagreement(
                            orderId, event.at(), state, OrderEventType.ACCEPTED_FROM_CLIENT,
                            "the order already exists"));

            case InboundEvent.SentToVenue sent -> {
                destinationId = sent.destinationId();
                destinationView = OrderView.of(sent.ourClOrdId(), clientView.fields())
                        .withField(FixTags.CL_ORD_ID, sent.ourClOrdId());
                apply(OrderEventType.SENT_TO_VENUE, event.at(), 0, out);
            }

            case InboundEvent.SendFailed failed ->
                    apply(OrderEventType.SEND_FAILED, event.at(), 0, out, failed.reason());

            case InboundEvent.CancelRequested request -> {
                PendingRequest cancel = PendingRequest.cancel(
                        request.clOrdId(), request.origClOrdId(),
                        request.clientClOrdId(), request.at());
                if (apply(OrderEventType.CANCEL_PENDING, event.at(), cumQty, out)) {
                    pending = cancel;
                    out.add(new OutboundEvent.RequestOutstanding(orderId, event.at(), cancel));
                }
            }

            case InboundEvent.ReplaceRequested request -> {
                PendingRequest replace = PendingRequest.replace(
                        request.clOrdId(), request.origClOrdId(),
                        request.clientClOrdId(), request.at(),
                        request.requestedTerms());
                if (apply(OrderEventType.REPLACE_PENDING, event.at(), cumQty, out)) {
                    pending = replace;
                    out.add(new OutboundEvent.RequestOutstanding(orderId, event.at(), replace));
                }
            }

            case InboundEvent.VenueReport report -> onReport(report, out);
        }
        return out;
    }

    private void onReport(InboundEvent.VenueReport report, List<OutboundEvent> out) {
        reportCount++;

        // The venue's own id is stable for the order's life, unlike ClOrdID
        // which changes on every replace, so it is worth recording the moment
        // it appears.
        if (report.venueOrderId() != null && venueOrderId == null) {
            venueOrderId = report.venueOrderId();
            if (destinationView != null) {
                destinationView = destinationView.withOrderId(report.venueOrderId());
            }
            // Tracked on the order rather than inferred from the destination
            // view, which is null until the order is sent — inferring from it
            // re-announced the same identifier on every report.
            out.add(new OutboundEvent.VenueIdLearned(
                    orderId, report.at(), report.venueOrderId()));
        }

        PendingRequest answered = pending;
        boolean moved = apply(report.type(), report.at(), report.cumQty(), out);

        // Which request this report answers, if any — it decides the
        // identifiers the client is told under.
        PendingRequest confirms = null;

        if (moved && answered != null && answers(report.type(), answered)) {
            confirms = answered;
            boolean accepted = report.type() == OrderEventType.CANCELLED
                    || report.type() == OrderEventType.REPLACED;
            pending = null;
            out.add(new OutboundEvent.RequestAnswered(
                    orderId, report.at(), answered, accepted));


            // Requested terms take effect only now. Treating them as current
            // while the request was outstanding would show an exposure that did
            // not exist.
            if (accepted && answered.isReplace() && destinationView != null) {
                for (Map.Entry<Integer, String> term : answered.requestedTerms().entrySet()) {
                    destinationView = destinationView.withField(term.getKey(), term.getValue());
                }
                // The replace's own ClOrdID now names the order at the venue.
                // A later cancel quoting the identifier that was replaced is
                // rejected as unknown, which reads as a lost order rather than
                // as the identifier bookkeeping it is.
                destinationView = destinationView
                        .withClOrdId(answered.clOrdId())
                        .withField(FixTags.CL_ORD_ID, answered.clOrdId());
                out.add(new OutboundEvent.TermsAmended(
                        orderId, report.at(), answered.requestedTerms()));
            }
        }

        // A fill ends the order and with it anything outstanding — there is
        // nothing left for the venue to cancel.
        if (state.isTerminal() && pending != null) {
            out.add(new OutboundEvent.RequestAnswered(orderId, report.at(), pending, false));
            pending = null;
        }

        // A report answering a cancel or replace goes back under the client's
        // identifier FOR THAT REQUEST, with 41 naming the order. A client
        // matching a cancel confirmation looks for the id it sent the cancel
        // with; sending the order's id back leaves the request unanswered as
        // far as the client can tell.
        if (confirms != null && confirms.clientClOrdId() != null) {
            out.add(new OutboundEvent.ForwardToClient(
                    orderId, report.at(),
                    confirms.clientClOrdId(), clientView.clOrdId()));

            // Only now: 41 on that confirmation names what the amendment
            // replaced, which is the identifier the client had until this
            // moment. Advancing before building it would have the report
            // replace itself.
            if (report.type() == OrderEventType.REPLACED) {
                clientView = clientView.withClOrdId(confirms.clientClOrdId());
                // And the terms it asked for, which the venue has just agreed
                // to. Carrying the identifier alone left the order reported at
                // the quantity it no longer has: an amendment from 1000 to
                // 1500, filled in full, read as 1500 done of 1000 — a sum that
                // says the record is wrong without saying which half.
                for (Map.Entry<Integer, String> term : confirms.requestedTerms().entrySet()) {
                    clientView = clientView.withField(term.getKey(), term.getValue());
                }
            }
        } else {
            out.add(new OutboundEvent.ForwardToClient(
                    orderId, report.at(), clientView.clOrdId(), null));
        }
    }

    /**
     * Run one event through the state machine and record what it did.
     *
     * @return true when the order advanced
     */
    private boolean apply(
            OrderEventType type, long at, double reportedCumQty, List<OutboundEvent> out) {

        return apply(type, at, reportedCumQty, out, null);
    }

    /**
     * @param reason what actually happened, when the caller knows something the
     *     state machine cannot: a link that was down, a venue's own words. It
     *     replaces the transition's generic description, because "the transport
     *     refused the message" tells a reader what to do next and "send failed"
     *     does not.
     */
    private boolean apply(
            OrderEventType type, long at, double reportedCumQty,
            List<OutboundEvent> out, String reason) {

        OrderStateMachine.Decision decision =
                OrderStateMachine.decide(state, type, reportedCumQty, cumQty, pending);

        return switch (decision) {
            case OrderStateMachine.Decision.Advance(OrderState to, String generic) -> {
                String because = reason == null ? generic : reason;
                OrderState from = state;
                double previousQty = cumQty;
                state = to;
                // A cumulative quantity normally only rises, and taking the
                // higher of the two absorbs a report that arrived out of order.
                // A correction or a bust is the exception: the venue is
                // withdrawing quantity it already reported, and clamping it
                // would leave a position the venue no longer agrees with.
                cumQty = revisesQuantity(type)
                        ? reportedCumQty
                        : Math.max(cumQty, reportedCumQty);

                if (from == to) {
                    // A partial while a request is outstanding: the quantity is
                    // the news, and the state deliberately stayed put.
                    out.add(new OutboundEvent.QuantityChanged(
                            orderId, at, state, previousQty, cumQty));
                } else {
                    // The order's current quantity, not the one it was
                    // created with: by this point an accepted amendment has
                    // already put the agreed terms on the client's view.
                    out.add(new OutboundEvent.StateChanged(
                            orderId, at, from, to, type, because, cumQty,
                            OrderFields.number(clientView.field(FixTags.ORDER_QTY))));
                }
                yield true;
            }
            case OrderStateMachine.Decision.Stale(var current, var cause, var why) -> {
                out.add(new OutboundEvent.Ignored(orderId, at, cause, why));
                yield false;
            }
            case OrderStateMachine.Decision.Illegal(var current, var cause, var why) -> {
                out.add(new OutboundEvent.Disagreement(orderId, at, current, cause, why));
                yield false;
            }
            case OrderStateMachine.Decision.Unchanged ignored -> false;
        };
    }

    /** Events where the venue restates a quantity rather than adding to it. */
    private static boolean revisesQuantity(OrderEventType type) {
        return type == OrderEventType.TRADE_CORRECTED
                || type == OrderEventType.TRADE_CANCELLED;
    }

    private static boolean answers(OrderEventType type, PendingRequest request) {
        return switch (type) {
            case CANCELLED, CANCEL_REFUSED -> request.isCancel();
            case REPLACED, REPLACE_REFUSED -> request.isReplace();
            default -> false;
        };
    }

    // ------------------------------------------------------------------
    // What it knows
    // ------------------------------------------------------------------

    public String orderId() {
        return orderId;
    }

    public synchronized OrderState state() {
        return state;
    }

    public synchronized double cumQty() {
        return cumQty;
    }

    public synchronized Optional<PendingRequest> pending() {
        return Optional.ofNullable(pending);
    }

    public String clientId() {
        return clientId;
    }

    public String sessionId() {
        return sessionId;
    }

    public synchronized String destinationId() {
        return destinationId;
    }

    public OrderView clientView() {
        return clientView;
    }

    public synchronized Optional<OrderView> destinationView() {
        return Optional.ofNullable(destinationView);
    }

    public synchronized long lastEventAt() {
        return lastEventAt;
    }

    public synchronized int reportCount() {
        return reportCount;
    }

    /**
     * Flatten to the stored form the journal and cache hold.
     *
     * <p>Taken under the same monitor as the updates, so a snapshot is never a
     * mixture of two events — a state from one and a quantity from the next
     * would journal a position that never existed.
     */
    public synchronized Order snapshot() {
        return new Order(
                orderId,
                sessionId,
                clientId,
                destinationId,
                clientView,
                OrderView.of(orderId, Map.of()),
                destinationView == null ? OrderView.of(orderId, Map.of()) : destinationView,
                state,
                cumQty,
                pending);
    }

    @Override
    public String toString() {
        return orderId + " " + state + " cum=" + cumQty
                + (pending == null ? "" : " [" + pending.kind() + " outstanding]");
    }
}
