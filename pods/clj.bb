(require '[bencode.core :as bencode]
         '[clojure.walk :as walk])

(def stdin (java.io.PushbackInputStream. System/in))

(defn write [v]
  (bencode/write-bencode System/out v)
  (.flush System/out))

(defn read-string [^"[B" v]
  (String. v))

(defn read []
  (bencode/read-bencode stdin))

(def debug? false)

(defn debug [& strs]
  (when debug?
    (binding [*out* (io/writer System/err)]
      (apply println strs))))

;; Custom pod ----

(def pid-file "pod-clj")

(def ^String host "127.0.0.1")

(defn connect-to-socket [port]
  (try
    (java.net.Socket. host ^long port)
    (catch java.net.ConnectException _)))


(defn connect-with-retry [port]
  (let [start (System/nanoTime)]
    (loop [i 1000]
      (if (zero? i)
        (throw (ex-info (str "Unable to connect within "
                             (long (/ (- (System/nanoTime) start) 1000 10000)) "ms")
                        {:host host :port port}))
        (if-let [connection (connect-to-socket port)]
          connection
          (do
            (Thread/sleep 2)
            (recur (dec i))))))))

(def *deps* (atom {}))

(defn set-clj-deps! [deps]
  (reset! *deps* deps))

;; REVIEW dynamic port based on deps hash?
(def port 49998)


(defn start-clj-process! [port deps]
  (let [cmd ["clojure"
             "-Sdeps" (pr-str deps)
             "-m" "pod.clj"
             (str port)
             "TODO-path-to-pid-file"]
        _ (debug "starting process" cmd)
        proc (-> (java.lang.ProcessBuilder. cmd)
                 (.redirectOutput java.lang.ProcessBuilder$Redirect/INHERIT)
                 (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
                 (.start))]
    (debug "process started" cmd)
    nil))

;; We need to work with delays in order to capture the deps as cli argument
(def *conn
  (delay
   (or (connect-to-socket port)
       (do (start-clj-process! port @*deps*)
           (connect-with-retry 49998)))))


(defn handle-prepl-output [in]
  (loop [output []]
    (debug "waiting ...")
    (let [{:keys [tag]
           value :val
           :as prepl-data} (clojure.edn/read-string (.readLine in))]
           (debug "prepl" prepl-data)
      (case tag
        :out (recur (conj output prepl-data))
        :ret
        ;; The last value is always return, so only stop here
        ;; REVIEW check if the pod protocol has something for output
        #_(conj output prepl-data)
        (let [value (clojure.edn/read-string value)]
          (if (:exception prepl-data)
            (do (debug prepl-data)
                (throw (ex-info "Backend error" value)))
            value))))))


(def *in (delay (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream @*conn)))))
(def *out (delay (java.io.PrintWriter. (.getOutputStream @*conn) true)))


(defn clj-eval* [code]
  (when-not (.isConnected @*conn)
    (throw (ex-info "Could not connect" {})))

  (binding [*out* @*out]
    (prn code))
  (handle-prepl-output @*in))


(def lookup {'pod.clj/eval clj-eval*})


(def describe-map
  '{:format :edn
    :namespaces [{:name pod.clj
                  :vars [{:name eval}]}]
    :opts {:shutdown {}}})

;; ---

(debug describe-map)


(defn run-pod []
  (loop []
    (let [message (try (read)
                       (catch java.io.EOFException _
                         (debug :EOF)
                         ::EOF))]

      (when-not (identical? ::EOF message)
        (debug ["message" message])
        (let [op (get message "op")
              op (read-string op)
              op (keyword op)
              id (some-> (get message "id")
                         read-string)
              id (or id "unknown")]
          (case op
            :describe (do (write describe-map)
                          (recur))
            :invoke (do (try
                          (let [var (-> (get message "var")
                                        read-string
                                        symbol)
                                args (get message "args")
                                args (read-string args)
                                args (edn/read-string args)]
                            (if-let [f (lookup var)]
                              (let [value (pr-str (apply f args))
                                    reply {"value" value
                                           "id" id
                                           "status" ["done"]}]
                                (write reply))
                              (throw (ex-info (str "Var not found: " var) {}))))
                          (catch Throwable e
                            (debug e)
                            (let [reply {"ex-message" (ex-message e)
                                         "ex-data" (pr-str
                                                    (assoc (ex-data e)
                                                           :type (class e)))
                                         "id" id
                                         "status" ["done" "error"]}]
                              (write reply))))
                        (recur))
            :shutdown (System/exit 0)
            (do
              (let [reply {"ex-message" "Unknown op"
                           "ex-data" (pr-str {:op op})
                           "id" id
                           "status" ["done" "error"]}]
                (write reply))
              (recur))))))))

(defn -main [deps]
  (set-clj-deps! (clojure.edn/read-string deps))
  (run-pod))

#?(:bb (apply -main *command-line-args*))
