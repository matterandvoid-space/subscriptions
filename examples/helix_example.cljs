(ns helix-example
  (:require
    ["react-dom" :as react-dom]
    ["react" :as react]
    [helix.dom :as dom]))

(defn D
  ([el] (react/createElement el))
  ([el props]
   (if (or (react/isValidElement props) (string? props) (number? props)
         (satisfies? INamed props))
     (react/createElement (name el) nil (cond-> props (satisfies? INamed props) name))
     (react/createElement (name el) props)))
  ([el props & c]
   (if (or (map? props) (and (object? props) (not (react/isValidElement props))))
     (do
       (println 'in 'one el)
       (react/createElement (name el) (clj->js props) (into-array c)))
     (do
       (println 'in 'two el)
       (react/createElement (name el) nil (into-array (into [props] c)))))))

(defn first-hook []
  (let [[the-count set-count] (react/useState 0)]
    (D :div
      (D :div "else")
      (D :button {:onClick #(set-count (inc the-count))}
        (str "count is " the-count)))))

(defn my-react-comp [props]
  (D first-hook)
  ;(dom/div nil (first-hook))
  #_(react/createElement "div" nil (first-hook)))

(defn ^:export init []
  (println "HELLO")
  (react-dom/render (my-react-comp nil) js/app))

(defn ^:dev/after-load refresh []
  (react-dom/render (my-react-comp nil) js/app)
  (println "HI")
  )

