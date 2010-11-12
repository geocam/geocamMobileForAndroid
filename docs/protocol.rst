GeoCam Mobile Protocol
======================

GeoCam Mobile's main purpose is to upload things to the server side that the
user deems is relevant to their situation.  All of this is in an effort to give
better situational awareness to whoever is seeing the incoming data.  That being
said, the client and server follow a certain protocol in order to actually get
the data from the phone, to the server.  This document describes the protocol
as seen (and sent) by the phone.

Authentication
--------------
Authentication has gone through two phases over the lifetime of the server and
application.  The first, which is obsolete and shouldn't be used, relied on a
secret key in the URL which was shared by the phone and the server.  This
essentially was the password for getting at the data.  When the user entered the
server in the Settings activity, they were forced to remember some secret,
long URL.  The user-name portion was also embedded in the URL.  Because this is
a legacy way of authenticating, it should *not* be used for anything but talking
to legacy servers.

The new and preferred way of authenticating is slightly better, but still not
perfect.  It utilizes HTTP Basic authentication over SSL.  The user-name and
password are put in the Settings activity.  There is some logic in the HTTP
utilities code which mostly takes care of this for you.  It knows that any
authenticated request goes though SSL and will hopefully setup the URL
correctly as well.

It is our hope, in the future, to use HTTP Digest authentication instead of
basic so we can get rid of SSL completely.

