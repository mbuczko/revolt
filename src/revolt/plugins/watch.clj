(ns ^{:clojure.tools.namespace.repl/load false} revolt.plugins.watch
  (:require [io.aviso.ansi]
            [clojure.java.io :as io]
            [clojure.java.classpath :as classpath]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [revolt.plugin :refer [Plugin create-plugin]]
            [revolt.task :as task]
            [revolt.watcher :as watcher]
            [revolt.utils :as utils]
            [revolt.bootstrap :as bootstrap]))

(defn init-plugin
  "Initializes filesystem watcher plugin."

  [{:keys [excluded-paths explicit-paths on-change]}]
  (reify Plugin
    (activate [this ctx]
      (log/debug "Starting filesystem watcher")
      (let [excludes   (utils/gather-paths excluded-paths)
            explicit   (utils/gather-paths explicit-paths)
            filesystem (java.nio.file.FileSystems/getDefault)
            root-dir   (utils/current-dir filesystem)
            classpaths (or (seq (map #(.toFile %) explicit))
                            (remove
                             #(contains? excludes (.toPath %))
                             (.classpaths ctx)))
            matchers   (reduce-kv (fn [m task pattern]
                                    (assoc m
                                           (.getPathMatcher filesystem pattern)
                                           (task/require-task-cached task)))
                                  {}
                                  on-change)]

        (println (io.aviso.ansi/yellow "Filesystem watcher initialized."))

        (apply watcher/watch-files
               (conj classpaths
                     (fn [{:keys [file]}]
                       (let [path (.relativize root-dir (.toPath file))]
                         (doseq [[matcher task] matchers]
                           (when (.matches matcher path)
                             (if-not task
                               (log/errorf "No task %s found to react on change of: %s" task path)
                               (do
                                 (log/debugf "changed: %s (=> %s)" path (task :describe))
                                 (task path)))))))))))

    (deactivate [this ret]
      (log/debug "closing watcher")
      (watcher/stop-watching ret))))
