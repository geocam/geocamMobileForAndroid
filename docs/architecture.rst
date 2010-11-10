GeoCam Mobile Architecture
==========================

Throughout this document I'm going to assume that the reader has a fairly good
grasp of the Android basics.  That is, they know what an Activity_, Service_ 
and other Android-related terms/jargon means.  For those who are unaware,
please familiiarize yourself with the fundamentals_ first.

.. _Activity: http://developer.android.com/reference/android/app/Activity.html
.. _Service: http://developer.android.com/reference/android/app/Service.html
.. _fundamentals: http://developer.android.com/guide/topics/fundamentals.html

Overview
--------
GeoCam Mobile is a series of activities that are centered around a core service.
Most of the activities exist to give an indication as to the current state of
the service, or provide inputs to the service.

The service, in turn, does most of the work with in the application.  It handles
delivering data to the server and keeping logs of where the phone goes.

Additionally, there are a few databases that keep a history of locations and
tracks, and a queue of things that need uploaded.

GeoCamService
-------------
GeoCamService_ is the core service of the entire application.  It is always
running when the application is running.  It handles the LocationManager_ for
the entire application and emits a broadcast intent when a new location
fix is known.  In this way, all of the location-based services are centralized.

**Note:** GeoCam Mobile only supports location updates from the GPS.  It will
quit if the user has it turned off and is unwilling (or unable) to turn it on.

GeoCamService_ tries to be smart with the rate at which it requests updates from
the GPS, only requesting frequent updates when any of the activities are in the
foreground.  See ForegroundTracker for how this is handled.

The service is also responsible for uploading any of the data products.
Currently this amounts to Photos and periodic live position updates.  There is
also support for uploading recorded GPS tracks, but the functionality is
currently disabled.

There are two upload mechanisms.  The first is a priority queue that takes data
products out of an upload queue (See GeoCamDbAdapter_) and uploads them to the
server.  This is where the bigger, formal data products are uploaded such as
images and tracks.  The second upload mechanism is an ad-hoc upload thread that
uploads live position updates.  We separated the two to be sure live updates
don't block due to a large image to get through.  (Although it might anyway, it
hasn't been tested.) For the protocol on how the server sees these data
products, see the protocol_ document.

The service talks to all of the other activities either though an broadcasted
Intent (for GPS location updates) or the GeoCamService AIDL.  This allows the
activities to ask it to increase the GPS rate, tell it to start and stop track
recording and let it know when the entire application is visible or not.

.. _GeoCamService: ../android/src/gov/nasa/arc/geocam/geocam/GeoCamService.java
.. _GeoCamDbAdapter: ../android/src/gov/nasa/arc/geocam/geocam/GeoCamDbAdapter.java
.. _LocationManager: http://developer.android.com/reference/android/location/LocationManager.html
.. _protocol: ./protocol.rst

Taking Photos
-------------
The most basic task that GeoCam Mobile strives to accomplish is uploading geo-
tagged, oriented photos to the server.  To do this, CameraActivity_ and 
CameraPreviewActivity_ were born.  CameraActivity_ takes care of actually
taking the picture, while CameraPreviewActivity_ allows the user to associate
an icon and notes with the picture before adding it to the upload queue for
the service.

CameraActivity_ has the additional tasks of listening to the accelerometer and
recording the orientation of when the image was captured as well as
camera-related things such as auto-focusing and actually taking the picture.

**Tangent:** If I were to rewrite this application, I would use the built-in camera
application and listen for orientation data elsewhere.  This allows a much more
integrated and streamlined interface.  This would have the added benefit of
allowing people to pick previous photos to upload as well, even if it means we
don't get precise camera orientation information (or perhaps location data) with
the picture.

The photo, if it's accepted to be uploaded, the service is notified and the image is thrown into the GeoCamDbAdapter_ to cue it for uploading.  Note, that due to the
protocol_ the image is actually added to the queue *three* times, one for each
downsample factor.

.. _CameraActivity: ../android/src/gov/nasa/arc/geocam/geocam/CameraActivity.java
.. _CameraPreviewActivity: ../android/src/gov/nasa/arc/geocam/geocam/CameraPreviewActivity.java

Recording Tracks
----------------
The second data product that the application supports uploading is GPS tracks.
While the software contains the code to do this, it is currently disabled by
hiding any buttons to get to the TrackMapActivity_.  Once enabled, the tracks
are recorded through the TrackMapActivity_ by talking to the service, which
handles recording the track points in the GpsDbAdapter_ and actually uploading
the tracks.

For details on how the tracks are actually uploaded, see the protocol_ document.

.. _TrackMapActivity: ../android/src/gov/nasa/arc/geocam/geocam/TrackMapActivity.java
.. _GpsDbAdapter: ../android/src/gov/nasa/arc/geocam/geocam/GpsDbAdapter.java

Status Screens
--------------
The home screen (GeoCamMobile_) and UploadPhotosActivity_ are mainly status
screens indicating the state of the service.  The home screen also gives access
to the different features of GeoCam (taking photos, etc).  The Upload activity
gives the status on how many things are in the upload queue and any errors that
may occur.

.. _GeoCamMobile: ../android/src/gov/nasa/arc/geocam/geocam/GeoCamMobile.java
.. _UploadPhotosActivity: ../android/src/gov/nasa/arc/geocam/geocam/UploadPhotosActivity.java

Other Activities
----------------
There is one little activity called AuthorizeUserActivity_.  This activity
exists purely for as a mechanism for deterring the general public from using
and uploading photos, even though it was published on the market.  Until the
user inputs a secret key, the application wouldn't start.  The secret key
can be found in the GeoCamMobile_ activity.

.. _AuthorizeUserActivity: ../android/src/gov/nasa/arc/geocam/geocam/AuthorizeUserActivity.java

Compatibility
-------------
GeoCam Mobile is targeted at the Android 2.2 SDK.  However, we also have legacy
devices, such as the HTC G1, which only run the 1.6 SDK, that we would like to
keep the software running on, if possible.  Because of the incompatibilies and
changes from the 1.6 to 2.2 version of the SDK, there are some utility classes
that provide shim layers and smooth out some of the bumps.  These can be found
in Reflect_.  Most of this is voodoo learned from Google I/O talks and deal
with the camera and service foregrounding.

.. _Reflect: ../android/src/gov/nasa/arc/geocam/geocam/util/Reflect.java
