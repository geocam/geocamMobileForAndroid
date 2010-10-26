#!/bin/sh
# __BEGIN_LICENSE__
# Copyright (C) 2008-2010 United States Government as represented by
# the Administrator of the National Aeronautics and Space Administration.
# All Rights Reserved.
# __END_LICENSE__

APK=$1

if [ -z "$APK" ]; then
  echo "usage: release.sh <my.apk>"
  exit 1
fi

APKP=${1%%.apk}
SIGNED=${APKP}-0-signed.apk
ALIGNED=${APKP}-1-aligned.apk

rm -f $SIGNED $ALIGNED
cp $APK $SIGNED
jarsigner -verbose -keystore $HOME/.android/GeoCamMobile.keystore $SIGNED GeoCamMobile
zipalign -v 4 $SIGNED $ALIGNED
echo --------------------------------------
echo now upload $ALIGNED
