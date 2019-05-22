(ns revolt.tasks.cljs
  (:require [revolt.utils :as utils]
            [clojure.java.io :as io]))

(defn invoke
  [ctx {:keys [builds compiler]} classpaths target inputs-fn build-fn]
  (let [assets (utils/ensure-relative-path target "assets")
        output (utils/ensure-relative-path target "out")]

    (run!
     (fn [build]
       (utils/timed
        (str "CLJS " {:id (:id build) :optimizations (-> build :compiler :optimizations)})
        (when-let [{:keys [output-to modules optimizations] :as compiler} (:compiler build)]
          (build-fn (apply inputs-fn (:source-paths build)) compiler)

          ;; for advanced-optimized builds copy generated javascripts into assets.
          ;; non-optimized builds are generated directly inside assets dir.

          (when (= :advanced optimizations)
            (println "CLJS (moving generated javascripts into assets)")
            (doseq [js (into [output-to] (map :output-to (vals modules)))]
              (when js
                (let [dest (io/file (.replaceFirst js "out" "assets"))]
                  (io/make-parents dest)
                  (io/copy (io/file js) dest)
                  (println js))))))))

     (eduction
      (map #(let [conf (update % :compiler merge compiler)
                  optm (keyword (or (-> conf :compiler :optimizations) "none"))
                  adv? (= :advanced optm)
                  dest (if adv? output assets)]

              (-> conf
                  (utils/dissoc-maybe [:compiler :preloads] adv?)
                  (assoc-in  [:compiler :optimizations] optm)
                  (update-in [:compiler :output-dir] (partial utils/ensure-relative-path dest))
                  (cond-> (-> conf :compiler :output-to)
                    (update-in [:compiler :output-to] (partial utils/ensure-relative-path dest)))
                  (cond-> (-> conf :compiler :modules)
                    (update-in [:compiler :modules] utils/ensure-relative-outputs dest)))))
      builds))
    ctx))
