GeoCam Mobile Documentation
===========================

Hardware Requirements
---------------------
GeoCam Mobile, being an Android application, is best tested on actual hardware.
The phone we are targeting the closest, and indeed, the only phone we officially
support, is the `Motorola Droid`_ with Android 2.2.  Even with this, we would
prefer to keep this application mostly compatible with Android 1.6 devices, such
as the `HTC G1`_.  The other device that this software has been tested to work on
is the `Nexus One`_.  If you have success with other Android-based phones, please
let us know!

With that, let's get the development environment setup and usable.

.. _`Motorola Droid`: http://www.motorola.com/Consumers/US-EN/Consumer-Product-and-Services/Mobile-Phones/Motorola-DROID-US-EN
.. _`HTC G1`: http://www.htc.com/www/product/g1/overview.html
.. _`Nexus One`: http://www.google.com/phone/detail/nexus-one

Software Requirements
---------------------
GeoCam Mobile has a few requirements that you'll have to have in order to get
started:

1) Git_: We use git for version control.  You probably already have it
   installed due to forking and cloning our project on GitHub_.  If not, follow
   the directions on the Git_ website.

2) Ant: While it's possible to use Eclipse with the Android SDK, we prefer
   using the command-line ant tool to build this android application.  It
   allows us to put the version in the About dialog of the application.  Your
   platform probably already has it installed, but if not, make sure to install
   it.  It is in the aptitude package manager on Ubuntu.

3) `The Android SDK`_: You will want to download the latest SDK tools (r07 at
   the time of writing.)  Follow the instructions_ to get it installed.  You
   will want to download the 2.2 SDK, as that is the API we compile against.

4) An editor: Vi(m), Emacs, etc. will work fine.

Note that we only have experience building on OS X (10.5 or higher) and Ubuntu
Linux 10.10.  Any Unix-like system should do.  Windows is a wild-card, use at
your own risk.

.. _`The Android SDK`: http://developer.android.com
.. _instructions: http://developer.android.com/sdk/installing.html

.. _Git: http://git-scm.com/
.. _GitHub: http://github.com

Setting up the Build Environment - Eclipse
------------------------------------------
**Note**: We include this for new Android developers, but please see the
non-Eclipse way below for the preferred way.

Please make sure your Eclipse environment is properly setup to do Android
development as per the instructions online_.

After you fork ande clone the GeoCam Moble project, you will want to import
the project into Eclipse.  Under *File->Import* menu option, select
*Existing Projects into Workspace*.  Select the GeoCamMobile top-level
directory as the root directory.  It should display "android" in the
available projects to import.  Select the android directory and click *Finish*.

You should be able to build and develop with GeoCam Mobile now.

.. _online: http://developer.android.com/sdk/installing.html

Setting up the Build Environment - non Eclipse
----------------------------------------------
**Note**: This is the preferred way of developing and building the GeoCam
Mobile software.  If you want to officially release the software, please
use ant.  It will embed the git commit and branch into the about dialog for 
the software.

After you fork and clone the GeoCam Mobile project, you will need to run some
commands to get it to compile with Ant properly.

First, we must find the ID of the 2.2 Google APIs that you installed with the
SDK:

::

    $ $PATH_TO_SDK/tools/android list target
    Available Android targets:
    id: 1 or "android-8"
         Name: Android 2.2
         Type: Platform
         API level: 8
         Revision: 2
         Skins: HVGA (default), WVGA854, WVGA800, QVGA, WQVGA400, WQVGA432
    id: 2 or "Google Inc.:Google APIs:8"
         Name: Google APIs
          Type: Add-On
          Vendor: Google Inc.
          Revision: 2
          Description: Android + Google APIs
          Based on Android 2.2 (API level 8)
          Libraries:
           * com.google.android.maps (maps.jar)
               API for Google Maps
          Skins: WQVGA400, WVGA854, HVGA (default), WQVGA432, QVGA, WVGA800

From the above, we can see the ID we want is 2.

Now we must update our build parameters to use this target.  In the android 
directory:

::

    $ $PATH_TO_SDK/tools/android update project -p . -t 2

Replace 2 with the ID that you found in the first step.

You should now be able to build the application with the following:

:: 

    $ ant debug

This should generate a bin/GeoCamMobile-debug.apk which you can install to your
device and test.
