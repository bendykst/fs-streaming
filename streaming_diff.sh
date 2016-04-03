#!/bin/bash
# Diff the output of two streamable.clj runs for a specific streaming source
# Usage: streaming_diff.sh source_tag file1 file2
colordiff \
  <(grep "\*\*$1\*\*" $2 | sed 's/|.*$//') \
  <(grep "\*\*$1\*\*" $3 | sed 's/|.*$//')
