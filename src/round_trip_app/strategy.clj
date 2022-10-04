(ns round-trip-app.strategy)

(defprotocol Strategy
  "Defines a strategy for protecting handlers from CSRF attacks. "
  (valid-token? [strategy request token])

  (get-token [strategy request])

  (write-token [strategy request response token]))