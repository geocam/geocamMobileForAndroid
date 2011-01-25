GeoCam Mobile Protocol
======================

GeoCam Mobile's main purpose is to upload things to the server that the user
deems is relevant to their situation.  All of this is in an effort to give
better situational awareness to whoever is seeing the incoming data.  That
being said, the client and server follow a certain protocol in order to
actually get the data from the phone, to the server.  This document describes
the protocol as seen (and sent) by the phone.

Throughout this document, I will use ``http://example.org/share/`` as the the 
base URL for all interactions with the server.  This is the URL the user has
specified in the settings and should not be taken literally.  Furthermore, I
will assume a user-name of ``joe_smith`` and a password of ``smithster``.
(Joe Smith is a very security-minded individual, as you can tell.)

Authentication
--------------
Authentication has gone through two phases over the lifetime of the server and
application.  The first, which is obsolete and shouldn't be used, relied on a
secret key in the URL which was shared by the phone and the server.  This
essentially was the password for authenticating posted data.  When the user
entered the server in the Settings activity, they were forced to remember some
secret, long URL.  The user-name portion was also embedded in the URL.  Because
this is a legacy way of authenticating, it should *not* be used for anything
but talking to legacy servers.

Old Style URL: ``http://example.org/share/135aedc3e2343ab/.../joe_smith/``

The new and preferred way of authenticating is slightly better, but still not
perfect.  It utilizes HTTP Basic authentication over SSL.  The user-name and
password are put in the Settings activity.  There is some logic in the HTTP
utilities code which mostly takes care of this for you.  It knows that any
authenticated request goes though SSL and will hopefully setup the URL
correctly as well.

It is our hope, in the future, to use HTTP Digest authentication instead of
Basic so SSL can be optional.

Server Responses
----------------
The sever will respond with appropriate HTTP response codes when and where
appropriate.  For example, 401 and 403 for authentication failures, 500
for server errors and 200 for OK.

If the response code is 200, a more specific response may be
included in the returned content.  See the appropriate subsection below for
details.

Uploading Photos
----------------
Uploading photos is probably the trickiest of all of the data products to
upload.  This is due to their size.  Because of the unreliability of the
cell networks in the places we hope to deploy this application, care must
be taken to get the data to the server when possible and as fast as
possible.  To accomplish this, we upload 3 different sizes of images.  One
is subsampled by a factor of 4, one by 2 and finally the full-sized image.
Furthermore, smaller images trump larger images.  This is to give the server
knowledge about photos, even if it doesn't have the best version of them.

These subsampled images are POSTed to the server in a ``multipart/form-data``
encoded fashion.  This is identical to how files are posted to the server
from webbrowsers.  The following variables are posted to the server to the 
following URLs:

Old URL: ``http://example.org/share/[secret]/upload/joe_smith/``

New URL: ``http://example.org/share/upload-m/``

POST Variables:

``uuid``
    The UUID of the photo.  This UUID is stable across all of the sizes of
    the picture that are uploaded.  This allows the server to replace the
    smaller-size image with the larger one after it is uploaded.

``cameraTime``
    The UTC time the picture was taken.  Formatted as ``YYYY-MM-DD HH:MM:SS``,
    that is, ISO-8601 without a timezone specifier.

``latitude``, ``longitude``
    GPS coordinates of where the photo was taken in decimal degrees.

``roll``, ``pitch``, ``yaw``
    The orientation, in degrees, of the phone when the photo was taken.

    **Notes:** The roll, pitch and yaw of the phone are modified such that it
    assumes the camera is taking pictures in landscape mode.

``yawRef``
    Whether the yaw (heading) of the roll pitch and yaw is true or magnetic.
    If true, value will be ``T``.  If magnetic, ``M``.

``notes``
    The notes that the user entered for the photo after taking it.

``tags``
    This is a misnomer -- it should be renamed "icon", as it only holds the 
    icon that the user selected after taking the photo.  The default photo 
    icon is called, unoriginally, ``default``.  See the icons in the
    resources directory for possible names.

``photo``
    Raw JPEG data.

The server will respond with an html document with a special token in the
response.  Somewhere towards the top of the document will contain the string
``GEOCAM_SHARE_POSTED``, along with the filename of the posted image.  This
corresponds to the filename you assigned to ``photo`` upload variable. This 
is, admittedly, sub-optimal.

Uploading Tracks
----------------
Tracks are uploaded to the server as `GPX 1.1`_ tracks.  When the user pauses
and restarts the track log, the GPX file starts a new segment.  There are some
geocam-specific extensions to the GPX specification (in the XML namespace
``geocam`` that are added to each track.  These are located in the
``extensions`` tag in the ``trk`` tag in the GPX file.  It will look like
this:

::

    <extensions>
      <geocam:uid>a_uid</geocam:uid>
      <geocam:icon>iconName</geocam:icon>
      <geocam:lineStyle>solid</geocam:lineStyle>
      <!-- lineStyle could also be 'dashed' -->
      <geocam:lineColor>#RRGGBBAA</geocam:lineColor>
    </extensions>

The entire GPX file is POSTed to the server in a ``multipart/form-data`` 
fashion, like photos.  Here are the URLs and fields:

Old URL: ``http://example.org/share/[secret]/track/upload/joe_smith/``

New URL: ``http://example.org/share/track/upload-m/``

POST variables:

``trackUploadProtocolVersion``
    Constant value: ``1.0``

``icon``
    The icon for the track.  Currently a constant value of ``camera``.
    Duplicated in the GPX file.

``uuid``
    The UUID of the track.  Duplicated in the GPX file as well.

``gpxFile``
    The GPX file.

The server will return the same thing as uploading a photo.

.. _`GPX 1.1`: http://www.topografix.com/GPX/1/1/

Live Position
-------------
Finally, the last data product that is uploaded are periodic live position
updates.  These are different from tracks in that they are live.  They are
not a storage of past points, but the point where the phone is actually at.
These points are uploaded to the server as a GeoJSON_ feature.  Uploads
will look like so:

URL: ``http://example.org/share/tracking/post/``

::

    { 
      "type": "Feature",
      "id": "4f9d40a3-fcf9-4a52-8790-ed60e7111518",
      "geometry": { 
        "type": "Point",
        "coordinates": [-122.06570,37.41937,0]
      },
      "properties": {
        "name": "joe_smith",
        "userName": "joe_smith",
        "timestamp": "2010-11-17T13:33:46Z",
        "accuracyMeters": 50.0,
        "speedMetersPerSecond": 1.0
      }
    }

**Note**: There is no old-style URL for this.  This is due to being a new
feature within GeoCam.  Since the user-name is stored in the GeoJSON_
properties, an old-style server could still handle these.

**Note 2**: The data is POSTed to the server **not** as a 
``multipart/form-data`` upload, as in pictures and tracks, but as the raw 
JSON, with a mime-type of ``application/json``.

The ``id`` is *not* a per-position UID.  This UID is assigned to the phone
when the application is started for the first time.  Any an all positions 
uploaded from this phone will have this UID.  This UID will change, of
course, if the application is uninstalled and then re-installed.  The MEID
of the phone could be used instead if a globally unique ID for the phone,
throughout the lifetime of the phone, is needed.

The ``coordinates`` are uploaded as [longitude,latitude,altitude].  Altitude
is optional.  ``accuracyMeters`` is in meters.  ``speedMetersPerSecond`` are,
unsurprisingly, in meters/second.

The duplicate ``name`` and ``userName`` fields is for the future.  It may
be the case that one user has multiple things they track.  The ``name`` could
change depending on the object being tracked, with the ``userName`` being
the user to whom these objects belong.

The server won't return anything besides the HTTP response code, which will
normally be 200 or 404 if the server is too old.

.. _GeoJSON: http://geojson.org/
