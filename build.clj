(ns build
  (:require
    [clojure.tools.build.api :as b]
    [clojure.string :as str]
    [org.corfield.build :as bb])
  (:import [java.time LocalDate]))

(def lib 'space.matterandvoid/subscriptions)
(def version (str/replace (str (LocalDate/now)) "-" "."))

;; run these to deploy a new version of the app
;; clojure -T:build jar
;; CLOJARS_USERNAME=... clojure -T:build deploy
;; git tag -a <version>
;; git push github --tags

(defn clean [opts] (bb/clean opts))

(defn jar [opts]
  (-> opts
    (bb/clean)
    (assoc :lib lib :version version :src-dirs ["src/main"] :src-pom "template/pom.xml")
    (bb/jar)))

(defn install [opts]
  (-> opts
    (assoc :lib lib :version version :src-dirs ["src/main"] :src-pom "template/pom.xml")
    (bb/install)))

(defn deploy "Deploy the JAR to Clojars." [opts]
  (-> opts
    (assoc :lib lib :version version)
    (bb/deploy)))
