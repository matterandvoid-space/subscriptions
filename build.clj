(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'space.matterandvoid/subscriptions)
(def version (format "0.6.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))

(def uber-file "target/uber.jar")

(defn clean [_] (b/delete {:path "target"}))

(defn prep [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src/main"]})
  (b/copy-dir {:src-dirs ["src/main"] :target-dir class-dir}))

(defn uber [_]
  (println "basis is: " basis)
  ;(b/compile-clj {:basis basis :src-dirs ["src/main"] :class-dir class-dir})
  (b/jar  {:class-dir class-dir
           :uber-file uber-file
           :basis basis}))

(defn all [_]
  (clean nil) (prep nil) (uber nil))

