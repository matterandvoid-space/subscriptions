(ns re-frame-subs.settings
  (:require
    [re-frame-subs.loggers :refer [console]]))

(def defaults
  {:loaded?             false
   :global-interceptors #?(:clj clojure.lang.PersistentQueue/EMPTY :cljs #queue [])})
