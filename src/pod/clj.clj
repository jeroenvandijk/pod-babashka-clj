(ns pod.clj)


(defn write-pid-file!
  [pid-file]
  ;; REVIEW consider adding pid-file support via
  #_(spit pid-file (pid/current))
  #_(pid/delete-on-shutdown! pid-file))


(defn -main [port pid-file]
  (let [port (Long/parseLong port)]
    (assert (< 0 port 65535))
    (clojure.core.server/start-server {:port port
                                       :name "pod-clj-backend"
                                       :accept 'clojure.core.server/io-prepl}))
  (write-pid-file! pid-file)


  @(promise))
