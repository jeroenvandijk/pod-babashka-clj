(require '[babashka.pods :as pods])

(pods/load-pod ["bb" "pods/clj.bb" (pr-str
  '{:deps {pod-clj {:git/url "https://github.com/jeroenvandijk/pod-babashka-clj.git" :sha "883bab734ada72476c372ce6d1077dfeac7bb9f9"}

           com.cognitect.aws/api       {:mvn/version "0.8.474"}
           com.cognitect.aws/endpoints {:mvn/version "1.1.11.842"}
           com.cognitect.aws/s3        {:mvn/version "809.2.734.0"}}})])

(require '[pod.clj])

;; Arbitrary Clojure can be evaluated
(println "1 + 2 =" (pod.clj/eval '(+ 1 2)))

;; But also Clojure libraries that don't work with Babashka
(pod.clj/eval '(do (require '[cognitect.aws.client.api :as aws])
                   (def s3 (aws/client {:api :s3}))
                   (with-out-str (aws/doc s3 :CreateBucket))))
