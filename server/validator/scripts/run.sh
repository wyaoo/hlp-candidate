#!/bin/bash -u -e -p

usage() {
    cat >&2 <<- EOF
Checks the longest blockchain from the top for structural and financial issues.

Usage:
  $(basename ${0}) [-d data_dir] [-m merkle_root_validation_block_limit] [-t transaction_validation_block_limit]

  data_dir                           - directory containing the database files, defaults to current dir
  merkle_root_validation_block_limit - on how many blocks from the top validate the merkle tree values, defaults to 0 meaning all
  transaction_validation_block_limit - on how many blocks from the top validate the transactions, defaults to 0 meaning all

IT RUNS FOR WEEKS WITH THE DEFAULT PARAMETERS. TRY TO RUN FIRST WITH:
  -m 100 -t 4

EOF
exit $1
}

DATA_DIR="."
MERKLE_TREE_VALIDATION_BLOCK_LIMIT=0
TRANSACTION_VALIDATION_BLOCK_LIMIT=0

main() {
    checkParams "$@"
    cd "$(dirname ${0})"
    java -Dlogback.configurationFile=../conf/logback.xml -jar ../target/hyperledger-server-validator-2.0.0-SNAPSHOT-shaded.jar "${DATA_DIR}" ${MERKLE_TREE_VALIDATION_BLOCK_LIMIT} ${TRANSACTION_VALIDATION_BLOCK_LIMIT}
    cd - > /dev/null
}

log() {
    echo -e "{$@}"
}

error() {
    echo -e "$@" >&2
}


checkParams() {
    while getopts ":d:m:t:h" optname
    do
        case "$optname" in
            "h")
                usage 0
            ;;
            "d")
                DATA_DIR="${OPTARG}"
            ;;
            "m")
                MERKLE_TREE_VALIDATION_BLOCK_LIMIT="${OPTARG}"
            ;;
            "t")
                TRANSACTION_VALIDATION_BLOCK_LIMIT="${OPTARG}"
            ;;
            *)
                error "Unknown argument ${OPTARG}\\n"
                usage 1
            ;;
        esac
    done
}

main "$@"
