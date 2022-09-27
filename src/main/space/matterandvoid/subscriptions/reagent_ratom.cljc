(ns space.matterandvoid.subscriptions.reagent-ratom
  (:require [space.matterandvoid.subscriptions.impl.reagent-ratom :as ratom]))

(defn cursor
  "Creates a Reagent RCursor type given a RAtom source (containing a hashmap) and a path into that hashmap."
  [#?(:cljs ^clj ratom :clj ratom) path]
  (ratom/cursor ratom path))

(defn make-reaction
  "Creates a Reagent Reaction using the supplied function."
  [f] (ratom/make-reaction f))

