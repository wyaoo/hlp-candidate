#!/bin/bash -x

# this code moved from http://jenkins.da-int.net:8080/job/hyperledger-rel/configure
#  which is now expected to call this code like:
#  ./jenkins/jenkins-setup-build-environment.sh

VERSION=$(grep "<version>" pom.xml | head -1 | cut -d ">" -f 2 | cut -d "<" -f 1)

if [[ $VERSION =~ -SNAPSHOT$ ]]
then
  ARTIFACTORY="https://digitalasset.artifactoryonline.com/digitalasset/webapp/#/artifacts/browse/tree/General/libs-snapshot-local/org/hyperledger/hyperledger/$VERSION"
else
  ARTIFACTORY="https://digitalasset.artifactoryonline.com/digitalasset/webapp/#/artifacts/browse/tree/General/libs-release-local/org/hyperledger/hyperledger/$VERSION"
fi

CURRENT_COMMIT_SHA=$(git log -n 1 --pretty=format:"%h")
echo "CURRENT_COMMIT_SHA: $CURRENT_COMMIT_SHA"

CURRENT_BRANCH="$(git branch -a --contains $CURRENT_COMMIT_SHA --merged | grep "remotes/origin/" | cut -d "/" -f "3")"
echo "CURRENT_BRANCH: $CURRENT_BRANCH"

git log --pretty=format:"Building commit <a href=\"https://github.com/DACH-NY/hyperledger/commit/%H\" title=\"%s\">[$CURRENT_BRANCH (%h)]</a> -&gt; <a href=\"$ARTIFACTORY\"><b>$VERSION</b></a> - %an eof building commit%n" -n 1

# %n puts a newline at the end, for the description regex in jenkins

