(ns revolt.tasks.cljs
  (:require [clojure.tools.logging :as log]
            [revolt.utils :as utils]))


(defn invoke
  [{:keys [builds optimizations]} classpaths target inputs-fn build-fn]

  (let [assets (utils/ensure-relative-path target "assets")
        output (utils/ensure-relative-path target "out")
        opt-kw (keyword optimizations)]

    (run!
     (fn [build]
       (utils/timed
        (str "CLJS " (:id build))
        (build-fn (apply inputs-fn (:source-paths build))
                  (:compiler build))))
     (eduction
      (map #(-> %
                (update-in [:compiler :output-to] (partial utils/ensure-relative-path assets))
                (update-in [:compiler :output-dir] (partial utils/ensure-relative-path (if (= opt-kw :advanced) output assets)))
                (update-in [:compiler :optimizations] (fn [current given] (or given current :none)) opt-kw)
                (as-> conf
                    (utils/dissoc-maybe conf [:compiler :preloads] (= (-> conf :compiler :optimizations) :advanced)))))
      builds))))
