# Name: CStr (convert to string)

# Syntax:
CStr(<value>)

π id, CStr(amount) → amount_text (Orders)

# Description:
CStr converts a value to its text form — turning a number, boolean, or date into a
string. Use it when you need a value as text, for example to build a label or
concatenate it with other text.

# Technical Description:
CStr(value) → STRING. Returns the value's canonical display string
(asDisplayString). A NULL argument is an evaluation error (CStr cannot convert
NULL). PURE, DETERMINISTIC.

# Examples:
Numeric id as text:
  π order_id, CStr(order_id) → ref (Orders)

Boolean flag as text:
  π id, CStr(active) → active_text (Accounts)

# Limitations:
Errors on NULL (guard with Nz/IsNull first). The output format is the value's
canonical display form.

# Alternatives:
CInt / CDbl convert toward numbers; to_date / to_timestamp / to_time parse a
string into a real temporal value.

# See Also:
[cint](cint.md), [cdbl](cdbl.md), [to_date](../datetime/to_date.md), [nz](../conditional/nz.md)
