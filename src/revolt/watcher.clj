(ns revolt.watcher
  (:require [clojure.tools.logging :as log])
  (:import (name.pachler.nio.file StandardWatchEventKind FileSystems WatchService Path Paths)
           (name.pachler.nio.file.ext Bootstrapper)
           (java.util.concurrent Executors)
           (java.util.concurrent Callable TimeUnit)
           (java.io File)))

(defn ^:private register-dir
  [^WatchService ws, ^File dir, watched-paths]
  (let [path (.. dir toPath toString)
        watch-key (.register (Paths/get path) ws
                             (into-array
                              (type StandardWatchEventKind/ENTRY_CREATE)
                              [StandardWatchEventKind/ENTRY_CREATE
                               StandardWatchEventKind/ENTRY_DELETE
                               StandardWatchEventKind/ENTRY_MODIFY]))]

    (swap! watched-paths conj {watch-key path})
    (log/debug "watching" path)

    (doseq [f (.listFiles dir) :when (. f isDirectory)]
      (register-dir ws f watched-paths))))

(defn watch-files [f & files]
  (let [pool (Executors/newSingleThreadExecutor)
        watched-paths (atom {})
        watch-service (.newWatchService (FileSystems/getDefault))]

    (try

      ;; register directories to be watched for changes

      (doseq [file files :when (and (.exists file) (.isDirectory file))]
        (register-dir watch-service file watched-paths))

      ;; to keep minimal level of concurrency, this callable fn waiting for
      ;; filesysytem events is submitted to single-thread pool and re-submits
      ;; itself later on, each time incoming event gets processed.

      (letfn [(callable []
                (let [sk (.take watch-service)
                      path (get @watched-paths sk)]

                  (doseq [ev (.pollEvents sk)]
                    (let [file (.. ev context getFile)]
                      (try
                        (f {:file (java.io.File. (str path "/" (.getPath file)))})
                        (catch Exception e
                          (log/error (.getMessage e))))))

                  ;; Cancel a key if the reset fails.
                  ;; This may indicate the path no longer exists.

                  (when-not (.reset sk)
                    (.cancel sk))

                  ;; resubmit itself
                  (.submit pool (cast Callable callable))))]

        (.submit pool (cast Callable callable)))

      (catch UnsupportedOperationException uox
        (log/error "File watching not supported!" uox))
      (catch java.io.IOException iox
        (log/error "I/O errors" iox)))

    pool))

(defn stop-watching [pool]
  (.shutdown pool))