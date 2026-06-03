(ns bitten.storage)

(defprotocol IStorage
  (migrate!      [backend])
  (insert-facts! [backend facts])
  (query-as-of   [backend opts]))
