# wait-for-ports

A command-line utility that waits until a port is open.

When starting a PostgreSQL Docker the port on the host
is bound immediately but the server does not respond - yet.
That confuses e.g. https://github.com/vishnubob/wait-for-it

This tool solves that use-case.
