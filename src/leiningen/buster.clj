(ns leiningen.buster
  (:require [cheshire.core :as chesh]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.pprint :refer [pprint]]
            [leiningen.buster.md5 :as md5]
            [leiningen.core.main :refer [debug *debug* info warn abort exit]]
            [leiningen.compile :as lcompile]
            [robert.hooke :as hooke])
  (:import  [java.util.regex Pattern]
            [java.io File]))


(defn- buster+ [s]
  (str "[buster]: " s))


(def ^:dynamic *log+* buster+)


(defn- ^String ensure-trailing-slash [s]
  (let [s (str s)]
    (if (.endsWith s "/") s (str s "/"))))


(defn- regex? [o]
  (instance? Pattern o))


(defn- name-parts
  [file]
  (-> file .getName (string/split #"\.")))


(defn- extension
  "Return only the extension part of the java.io.File instance."
  [file]
  (-> file name-parts last))


(defn- basename
  "Return the name part of the java.io.File instance without extension."
  [file]
  (->> file name-parts butlast (string/join ".")))


(defn- digest
  "Return an MD5 digest for the java.io.File instance."
  [file]
  ;; We're only taking the first 10 characters of the MD5 here to be in sync
  ;; with how rev-gulp manages its file revisions... it's up for debate as to
  ;; whether this is correct or not and probably doesn't matter a whole lot
  ;; whether it's long or short.
  (apply str (take 10 (md5/md5-file file))))


(defn- fingerprinted-file
  "File handle for a fingerprinted version of the provided file."
  [file]
  (io/file (.getParent file)
           (format "%s-%s.%s" (basename file) (digest file) (extension file))))


(defn manifest-map
  "Return existing manifest map or a new hash-map."
  [manifest use-existing?]
  (if (and use-existing? (.exists manifest))
    (chesh/parse-string (slurp manifest))
    {}))


(defn- ^String strip-base [base file]
  (let [base (str base)
        file (str file)]
    (.substring file (count base) (count file))))


(defn- bust-paths!
  "Create fingerprinted files for provided resource paths suitable for use in browser cache-busting.
  e.g. `(bust-paths! \"resources/rev-manifest.json\" [\"resources/public/foo.css\"])'
  would create a \"resources/foo-acbd18db4c.css\" file."
  [files-list files-base output-base manifest-file merge?]
  (loop [files    files-list
         mappings (manifest-map manifest-file merge?)]
    (if-let [file0 (first files)]
      (let  [file-rev (fingerprinted-file file0)
             file0-rest (strip-base files-base file0)
             file-rev-rest (strip-base files-base file-rev)
             file1 (io/file output-base file-rev-rest)]

        (debug (buster+ (format "Writing %s" file1)))
        (.mkdirs (.getParentFile file1))
        (io/copy file0 file1)
        (recur (rest files) (assoc mappings file0-rest file-rev-rest)))

      (->> mappings
           (#(chesh/generate-string % {:pretty true}))
           (spit manifest-file)))))


(def files-list
  "Function returns a file-seq of all absolute path files in 'dir'"
  (memoize
    (fn [dir]
      (file-seq dir))))


(defn- match-files
  "Returns a sequence of all absolute path files matching the regex-pattern 'p' in files-base"
  [files-base p]
  (filter #(re-find p (str %)) (files-list (io/file files-base))))


(defn- expand-file
  "Returns a sequence of zero (if not exists), one (if isFile) or more (if isDirectory) absolute path files."
  [file]
  (if (.exists file)
    (if (.isFile file)
      (list file)
      (map expand-file (.listFiles file)))
    (do
      (warn (*log+* (format "File does not exist: %s" file)))
      (list))))


(defn ^File project-based
  "Returns a file with 'path' appended to project's path"
  [project path]
  (io/file (:root project) path))


(defn expand-files
 ([project files]
  (expand-files project (project-based project "") files))
 ([project files-base files]
  (distinct
    (flatten
      (for [o files]
        (if (regex? o)
          (map expand-file (match-files files-base o))
          (let [f (project-based project o)]
            (expand-file f))))))))


(defn- assert-files [files]
  (assert (sequential? files)
          (buster+ "project.clj [:buster :files] must be a sequential of one or more string or regex-s."))
  (when (empty? files)
    (abort (buster+ "project.clj [:buster :files] is empty."))))


(defn buster
  "Run buster on a project. This doubles as the standalone task entrypoint and the entrypoint for the compile hook."
  [{{:keys [files files-base output-base manifest merge] :as buster} :buster :as project} & args]
  (assert-files files)
  (let [project-baser (partial project-based project)

        files-base   (ensure-trailing-slash
                       (project-baser (or files-base "")))
        output-base  (ensure-trailing-slash
                       (if output-base
                         (project-baser output-base)
                         files-base))
        manifest     (if manifest
                       (project-baser manifest)
                       (io/file output-base "rev-manifest.json"))
        merge        (boolean merge)

        files-list   (expand-files project files-base files)]

    (debug (buster+ (format "Using:
  files-base:  %s
  output-base: %s
  manifest:    %s
  merge:       %s" files-base output-base manifest merge)))

    (bust-paths! files-list files-base output-base manifest merge)))


(defn- compile-hook
  "Runs buster after compilation completes."
  [f project & args]
  (apply f project args)
  (buster project))


(defn activate
  "lein calls this to register any hooks we specify."
  []
  (hooke/add-hook #'lcompile/compile #'compile-hook))
