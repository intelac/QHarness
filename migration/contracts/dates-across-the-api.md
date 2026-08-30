# Dates crossing the API boundary

status: proposed
drafted-from: booking sample, template scan

## The case

[read] Templates format dates in the view. Seven such calls, all on dates or
money:

```
Hotels/confirmBooking.html:33   booking.total.formatCurrency()
Hotels/confirmBooking.html:36   booking.checkinDate.format()
Hotels/confirmBooking.html:37   booking.checkinDate.format()
Hotels/confirmBooking.html:40   booking.checkoutDate.format()
Hotels/confirmBooking.html:41   booking.checkoutDate.format()
Hotels/index.html:94            booking.checkinDate.format()
Hotels/index.html:95            booking.checkoutDate.format()
```

[read] The action passes the entity, not a formatted string —
`render(booking)` in `Hotels.confirmBooking`. Formatting happens entirely in
the view, so the boundary between "the data" and "how it reads" did not exist
in Play. It has to exist now, and where it falls is a decision.

[inferred] `checkinDate` and `checkoutDate` are compared: a booking is invalid
unless check-in precedes check-out. The comparison is not visible in these
templates, so this rests on the field names and the domain rather than on code
read. Worth confirming in `Booking.java` before this is accepted.

## Options

**A. ISO 8601 over the wire, client formats.**

The server does not know the viewer's locale; the browser does. Values stay
ordered and comparable in transit, so sorting a table or validating check-in
against check-out is arithmetic rather than string handling.

Cost: every React component displaying a date needs a formatting helper. One
helper, imported in many places.

**B. Formatted string over the wire.**

Closest to what the old page emitted — the migrated page is a more literal
copy, and nothing on the client needs a date library.

Cost: the value stops being a date. Any later sorting, filtering or comparison
on the client operates on strings, and the server has to choose a locale it
cannot know. Reversing this later means changing the API and every consumer.

**C. Both — `checkinDate` and `checkinDateFormatted`.**

Nothing breaks either way.

Cost: two fields that can disagree, and no rule saying which is authoritative.
The formatted one will be read by something that should have read the other.

## Recommendation

**A.**

B is cheaper today and forecloses the comparison these particular dates need.
C postpones the decision by shipping both answers, which is how a field pair
drifts.

The recommendation is A; the decision is not mine to make.

## How it would apply

Any `Date`, `DateTime` or `LocalDate` crossing the API is serialised ISO 8601
in UTC.

A template calling `.format()` on a date does **not** make that field a string
in the DTO. It records a formatting requirement for the React component that
replaces the template — the field stays a date, and the formatting moves.

**Money is deliberately out of scope.** `formatCurrency()` looks like the same
question and is not: currency carries a unit, and rounding is a domain rule
rather than a display choice. It needs its own contract.

## Where this was already applied

*(nothing yet — this contract is proposed)*
