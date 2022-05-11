(ns space.matterandvoid.subscriptions.impl.loggers
  (:require [clojure.set :refer [difference]]))

#?(:clj (defn log [& args]))

(def ^:private loggers
  "Holds the current set of logging functions.
   By default, re-frame uses the functions provided by js/console.
   Use `set-loggers!` to change these defaults
  "
  (atom #?(:cljs {:log       (js/console.log.bind   js/console)
                  :info      (js/console.log.bind   js/console)
                  :warn      (js/console.warn.bind  js/console)
                  :error     (js/console.error.bind js/console)
                  :debug     (js/console.debug.bind js/console)
                  :group     (if (.-group js/console)         ;; console.group does not exist  < IE 11
                               (js/console.group.bind js/console)
                               (js/console.log.bind   js/console))
                  :groupEnd  (if (.-groupEnd js/console)        ;; console.groupEnd does not exist  < IE 11
                               (js/console.groupEnd.bind js/console)
                               #())})
        ;; clojure versions
        #?(:clj {:log      (partial log :info)
                 :warn     (partial log :warn)
                 :error    (partial log :error)
                 :debug    (partial log :debug)
                 :group    (partial log :info)
                 :groupEnd  #()})))

(defn console
  [level & args]
  (assert (contains? @loggers level) (str "re-frame: log called with unknown level: " level))
  (apply (level @loggers) args))
