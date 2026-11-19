set -eu
for scenario in immediate transient permanent; do
  for dockerfile in gateway-service/Dockerfile core-service/Dockerfile; do
    retry_command=$(awk '/^RUN for attempt in 1 2 3; do/ { active=1; sub(/^RUN /, "") } active { print; if ($0 ~ /done$/) exit }' "$dockerfile" | tr -d '\r')
    scenario_state=$(mktemp)
    printf '0' > "$scenario_state"
    mvn() {
      local batch_option=$1 transfer_option=$2 update_option=$3 goal=$4
      count=$(cat "$scenario_state")
      count=$((count + 1))
      printf '%s' "$count" > "$scenario_state"
      [ "$batch_option" = '-B' ] && [ "$transfer_option" = '-ntp' ] && [ "$update_option" = '-U' ] && [ "$goal" = 'dependency:go-offline' ] || return 99
      case "$scenario" in
        immediate) return 0 ;;
        transient) [ "$count" -ge 3 ] ;;
        permanent) return 42 ;;
        *) return 99 ;;
      esac
    }
    sleep() { local seconds=$1; [ "$seconds" = '5' ]; }
    result=0
    ( eval "$retry_command" ) || result=$?
    count=$(cat "$scenario_state")
    rm "$scenario_state"
    case "$scenario" in
      immediate) { [ "$result" -eq 0 ] && [ "$count" -eq 1 ]; } || exit 1 ;;
      transient) { [ "$result" -eq 0 ] && [ "$count" -eq 3 ]; } || exit 1 ;;
      permanent) { [ "$result" -ne 0 ] && [ "$count" -eq 3 ]; } || exit 1 ;;
      *) exit 1 ;;
    esac
    printf '%s: %s passed\n' "$dockerfile" "$scenario"
  done
done
