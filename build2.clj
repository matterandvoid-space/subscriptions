(ns build2
  (:require
    [clojure.tools.build.api :as b]
    [org.corfield.build :as bb]))

(def lib 'myname/mylib)
;; if you want a version of MAJOR.MINOR.COMMITS:
(def version (format "1.0.%s" (b/git-count-revs nil)))


(defn jar-it
  [opts]
  (-> opts
    (assoc :lib lib :version version :src-dirs ["src/main"])
    (bb/clean)
    (bb/jar)))
