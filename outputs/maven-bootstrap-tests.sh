set -eu
for scenario in immediate transient permanent; do
  state_file=$(mktemp)
  printf '0' > "$state_file"
  bash() {
    local wrapper_script=$1 version_option=$2
    [ "$wrapper_script" = 'mvnw' ] && [ "$version_option" = '-v' ] && [ "$MVNW_VERBOSE" = 'true' ] || return 99
    count=$(cat "$state_file")
    count=$((count + 1))
    printf '%s' "$count" > "$state_file"
    case "$scenario" in
      immediate) return 0 ;;
      transient) [ "$count" -ge 3 ] ;;
      permanent) return 1 ;;
      *) return 99 ;;
    esac
  }
  sleep() { local seconds=$1; [ "$seconds" = '5' ]; }
  result=0
  ( . ./outputs/maven-bootstrap-command.sh ) || result=$?
  count=$(cat "$state_file")
  rm "$state_file"
  case "$scenario" in
    immediate) { [ "$result" -eq 0 ] && [ "$count" -eq 1 ]; } || exit 1 ;;
    transient) { [ "$result" -eq 0 ] && [ "$count" -eq 3 ]; } || exit 1 ;;
    permanent) { [ "$result" -ne 0 ] && [ "$count" -eq 3 ]; } || exit 1 ;;
    *) exit 1 ;;
  esac
  printf 'Maven bootstrap: %s passed\n' "$scenario"
done
