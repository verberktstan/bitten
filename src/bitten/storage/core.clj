(ns bitten.storage.core)

(defprotocol IStorage
  (migrate!      [backend])
  (insert-facts! [backend facts])
  (query-as-of   [backend opts]))