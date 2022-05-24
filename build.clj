(ns build
  (:require
    [clojure.tools.build.api :as b]
    [clojure.string :as str]
    [org.corfield.build :as bb])
  (:import [java.time LocalDate]))

(def lib 'space.matterandvoid/subscriptions)
(def version (str/replace (str (LocalDate/now)) "-" "."))

(defn jar-it [opts]
  (-> opts
    (bb/clean)
    (assoc :lib lib :version version :src-dirs ["src/main"] :src-pom "template/pom.xml")
    (bb/jar)))

(defn deploy "Deploy the JAR to Clojars." [opts]
  (-> opts
    (assoc :lib lib :version version)
    (bb/deploy)))
