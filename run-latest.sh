#!/bin/sh

set -o errexit
set -o nounset
#set -o xtrace

OS="$(uname)"
if [ "${OS}" = "Linux" ]; then
  	platform=linux
elif [ "${OS}" = "Darwin" ]; then
  	platform=macos
else
	platform=windows
fi

cleanup() {
    currentExitCode=$?
    rm -f "./wait-for-ports"
    exit ${currentExitCode}
}

trap cleanup INT TERM EXIT

wget --quiet \
    --output-document="./wait-for-ports" \
    "https://github.com/ArloL/wait-for-ports/releases/latest/download/wait-for-ports-${platform}"

chmod +x "./wait-for-ports"

"./wait-for-ports" --version

"./wait-for-ports" "$@"
