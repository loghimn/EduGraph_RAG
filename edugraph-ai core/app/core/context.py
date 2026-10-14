import contextvars

heartbeat_cb_var = contextvars.ContextVar("heartbeat_cb", default=None)
