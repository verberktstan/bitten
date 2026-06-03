(ns bitten.protocol)

(defn parse-request [line]
  (try
    (or (clojure.edn/read-string line) {:error "empty request"})
    (catch Exception e
      {:error (ex-message e)})))

(defn serialize-response [response]
  (pr-str response))
