#!/bin/bash -u

usage() {
    cat >&2 <<- EOF
  Usage: invoke it from scripts or from the directory of the project

    $(basename ${0}) [-h] [-b] [-c] [-g]

      -b  - starts the HyperLedger implementation of the bitcoin server, cannot use with -c
      -c  - starts the original core implementation of the bitcoin server, cannot use with -b
      -g  - generate 2015 blocks. Do not run it multiple times, because it will take twoo weeks to complete
      -h  - prints this help

    If you want to see server logs, then you can also start the core bitcoin server in another terminal with:
      bitcoind -regtest -rpcuser=t -rpcpassword=t -datadir="data_dir" --printtoconsole -server -debug
EOF
    exit ${1:-0}
}

initServer() {
    local server="$1"
    local generateBlocks="$2"

    if [[ "${server}" == "hyperledger" ]]; then
        error "The hyperledger server is not supported yet."
        exit 2
    elif [[ "${server}" == "core" ]]; then
        mkdir -p "${DATA_DIR}"
        echo "Starting core server..."
        bitcoind -regtest -rpcuser=t -rpcpassword=t -datadir="${DATA_DIR}" -server -debug &
        sleep 10
        if [[ "${generateBlocks}" == "true" ]]; then
            echo "Generating blocks. This may take a while..."
            bitcoin-cli -rpcuser=t -rpcpassword=t -datadir="${DATA_DIR}" -regtest generate 2015
        fi
    fi
    #statements

}

cleanup() {
    set +u
    local server="$1"
    set -u
    # echo "**** $1"

    if [[ "${server}" == "hyperledger" ]]; then
        echo "The hyperledger server would be stopped now..."
    elif [[ "${server}" == "core" ]]; then
        echo "Stopping core server..."
        bitcoin-cli -rpcuser=t -rpcpassword=t -datadir="${DATA_DIR}" -regtest stop
    fi
}

DATA_DIR="${HOME}/tmp/btc"

main() {
    processParams "$@"

    trap 'cleanup "${START_SERVER}"' EXIT SIGINT SIGKILL SIGTERM SIGSTOP SIGABRT

    initServer "${START_SERVER}" ${GENERATE_BLOCKS}

    if [[ "scripts" == "$(basename ${PWD})" ]]
    then
        cd ..
    fi
    mvn test -PrunStressTest
}

checkOtherServerParam() {
    local server="$1"

    if [[ "${server}" != "none" ]]; then
        error "Only one of -b and -c can be specified\\n"
        usage 3
    fi

}

GENERATE_BLOCKS="false"
START_SERVER="none"
processParams() {
    # :x expects a value for -x
    # h expects no value for -h
    while getopts "bcgh" optname
    do
        case "${optname}" in
            "b")
                checkOtherServerParam "${START_SERVER}"
                START_SERVER="hyperledger"
            ;;
            "c")
                checkOtherServerParam "${START_SERVER}"
                START_SERVER="core"
            ;;
            "g")
                GENERATE_BLOCKS="true"
            ;;
            "h")
                usage
            ;;
            *)
                usage 1
            ;;
        esac
    done
}

log() {
    echo "{$@}"
}

error() {
    echo -e "$@" >&2
}

main "$@"
