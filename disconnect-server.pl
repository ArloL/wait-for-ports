#!/usr/bin/env perl

use strict;
use warnings;
use feature 'say';

use IO::Socket;

my $server = IO::Socket::INET->new(
    LocalPort => 58258,
    Type      => SOCK_STREAM,
    Reuse     => 1,
    Listen    => 10 )
    or die "Couldn't be a tcp server on port 58258 : $@\n";

while (my $client = $server->accept()) {
    say "connected. immediately disconnecting.";
    $client->close();
}

close($server);
