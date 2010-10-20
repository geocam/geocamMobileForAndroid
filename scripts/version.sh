#!/bin/sh

VERSION_FILE=res/values/version.xml

FULL_REF=$(git symbolic-ref HEAD)
FULL_COMMIT=$(git rev-parse HEAD)

BRANCH=${FULL_REF##refs/heads/}
COMMIT=$(echo $FULL_COMMIT | cut -c1-7)
DATE=$(date "+%Y-%d-%m")

(
cat <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <string name="version_git_branch">$BRANCH</string>
  <string name="version_git_commit">$COMMIT</string>
  <string name="version_date">$DATE</string>
</resources>
EOF
) > $VERSION_FILE
