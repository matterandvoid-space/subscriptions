(ns re-frame.settings
  (:require
    [re-frame.interop :as interop]
    [re-frame.loggers :refer [console]]))

(def defaults
  {:loaded?             false
   :global-interceptors interop/empty-queue})

(def store
  (atom defaults))

(interop/on-load
  #(swap! store (fn [m] (assoc m :loaded? true))))

(defn loaded?
  []
  (:loaded? @store))
