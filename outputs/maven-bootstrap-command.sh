for attempt in 1 2 3; do
  if MVNW_VERBOSE=true bash mvnw -v; then exit 0; fi
  if [ "$attempt" -eq 3 ]; then exit 1; fi
  echo "Maven bootstrap failed; retrying in 5 seconds ($attempt/3)." >&2
  sleep 5
done