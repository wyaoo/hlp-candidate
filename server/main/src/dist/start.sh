#!/bin/bash -u

## Usage:
##   start.sh [config_file]

pushd `dirname $0` > /dev/null
scriptPath=`pwd`

# there supposed to be only one such jar, but find the latest one if more were put here manually
jarFile="$(ls -1 hyperledger-server-main-*-shaded.jar | sort | tail -1)"
lockFile="${scriptPath}/PID.LOCK"

# get a lock file to write the pid into
lockfile -r 0 "${lockFile}" 2>/dev/null
# if we could not acquire the lock file because it exists already
if [[ $? != 0 ]]
then
    oldPid=$(cat "${lockFile}")
    # test if the process still running
    ps "${oldPid}" | grep -q "${oldPid}"
    if [[ $? == 0 ]] 
    then
	  	echo -e "Application is already running with pid: ${oldPid}"
		exit 1
	else
		# delete and reacquire the abandoned lock file
		rm -f "${lockFile}"
		lockfile -r 0 "${lockFile}"
	fi
fi
# if we are here we successfuly acquired the lock file by now

java -jar ${jarFile} "$@" &
pid=$!
# store the pid in the lock file
chmod +w "${lockFile}"
echo -e "${pid}" > "${lockFile}"
chmod -w "${lockFile}"

echo "Started successfully"

popd > /dev/null
