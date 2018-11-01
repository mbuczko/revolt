(ns revolt.tasks.cljs
  (:require [clojure.tools.logging :as log]
            [revolt.utils :as utils]))

(defn invoke
  [ctx {:keys [builds compiler]} classpaths target inputs-fn build-fn]
  (let [assets (utils/ensure-relative-path target "assets")
        output (utils/ensure-relative-path target "out")]

    (run!
     (fn [build]
       (utils/timed
        (str "CLJS "  build)
        (build-fn (apply inputs-fn (:source-paths build))
                  (:compiler build))))
     (eduction
      (map #(let [conf (update % :compiler merge compiler)
                  optm (keyword (or (-> conf :compiler :optimizations) "none"))
                  adv? (= :advanced optm)]

              (-> conf
                  (utils/dissoc-maybe [:compiler :preloads] adv?)
                  (update-in [:compiler :optimizations] (constantly optm))
                  (update-in [:compiler :output-dir] (partial utils/ensure-relative-path (if adv? output assets)))
                  (cond-> (-> conf :compiler :output-to)
                    (update-in [:compiler :output-to] (partial utils/ensure-relative-path assets)))
                  (cond-> (-> conf :compiler :modules)
                    (update-in [:compiler :modules] utils/ensure-relative-outputs assets)))))
      builds))
    ctx))
