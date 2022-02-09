(ns re-frame-subs.utils
  (:require
    [re-frame-subs.loggers :refer [console]]))

(defn first-in-vector
  [v]
  (if (vector? v)
    (first v)
    (console :error "re-frame: expected a vector, but got:" v)))
