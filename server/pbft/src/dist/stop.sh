#!/bin/bash -u

## Usage:
##   stop.sh

pushd `dirname $0` > /dev/null
scriptPath=`pwd`

lockFile="${scriptPath}/PID.LOCK"
timeout=15
increment=1
cumulativeTime=0

if [[ -f "${lockFile}" ]]
then
	pid="$(cat "${lockFile}")"
	echo "Stopping and waiting ${timeout} seconds for a graceful exit"
	kill "${pid}"
	# give some time for graceful exit
	while [[ $cumulativeTime -lt $timeout ]] && ps "${pid}" | grep -q "${pid}" 
	do
		sleep 1
		(( cumulativeTime += 1 ))
	done

	# kill forcefully if it is still running
	if ps "${pid}" | grep -q "${pid}"
	then
		echo "Stopping forcefuly, no data will be lost or compromised in the database"
		kill -9 "${pid}"
	fi
	# don't leave teh lock file behind	
	rm -f "${lockFile}"
	echo "Stopped"
else
	echo "Application is not running"
fi

popd > /dev/null
