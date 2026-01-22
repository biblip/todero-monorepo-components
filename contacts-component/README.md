# Contacts Component

Provides a simple contact directory for agents and other components (e.g., Gmail send workflows).
Contacts are stored in `contacts.json` inside the component storage directory.

## Commands

- `add` - Add or update a contact
- `list` - List all contacts
- `find` - Find contacts by name or email
- `group` - List contacts in a group
- `remove` - Remove a contact by email

## Examples

```
com.shellaia.verbatim.component.contacts add name=Alice email=alice@example.com groups=team,ops
com.shellaia.verbatim.component.contacts add name=Bob email=bob@example.com groups=team
com.shellaia.verbatim.component.contacts list
com.shellaia.verbatim.component.contacts find query=alice
com.shellaia.verbatim.component.contacts group name=team
com.shellaia.verbatim.component.contacts remove email=alice@example.com
```

## Storage

The component stores contacts in:
- `contacts.json`

This file is managed automatically by the component.
