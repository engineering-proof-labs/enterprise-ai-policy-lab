# Ticket routing guide

## Billing tickets

Route billing tickets to the Billing Operations queue. Include the ticket ID and the invoice number if one is available.

## Password reset

Send password reset requests to the Account Access queue after verifying the requester through the standard identity check.
