(ns br.dev.zz.oiti
  (:refer-clojure :exclude [load])
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [clojure.string :as string]
    [ring.core.protocols :as rcp])
  (:import (org.snakeyaml.engine.v2.api Load LoadSettings)))

;;https://spec.openapis.org/oas/3.1/schema/2022-02-27

(set! *warn-on-reflection* true)

(defn load
  [x]
  (let [loader (Load. (.build (LoadSettings/builder)))]
    (with-open [rdr (io/reader x)]
      (.loadFromReader loader rdr))))

(def http-methods
  #{"delete" "get" "head" "options" "patch" "post" "put" "trace"})
(defn path->json-pointer
  [vs]
  (str "#" (string/join "/"
             (cons ""
               (map (fn [v]
                      (-> v
                        str
                        (string/replace #"~" "~0")
                        (string/replace #"/" "~1")))
                 vs)))))


(defn json-pointer->path
  [s]
  (mapv (fn [s]
          (-> s
            str
            (string/replace #"~1" "/")
            (string/replace #"~0" "~")))
    (rest (string/split s #"/"))))
(defn $deref
  [document {:strs [$ref]
             :as   object}]
  (if $ref
    ($deref document (get-in document (json-pointer->path $ref)))
    object))


(defn path-regex
  [path]
  (string/replace path #"(\{[^\}]+\})" "([^/]+)"))

(defn path-params-names
  [path]
  (loop [input path
         params []]
    (let [[match param] (re-find #"\{([^\}]+)\}"
                          input)]
      (if param
        (recur (string/replace input match "")
          (conj params param))
        params))))

(s/def ::document some?)
(s/def ::handlers (s/map-of string? fn?))

(defn match-route?
  [uri {::keys [path-re params-names]
        :as    path-info}]
  (when-let [match (re-matches path-re uri)]
    (merge path-info
      (when params-names
        {::path-params (zipmap params-names
                         (rest match))}))))
(defn ->ring-handler
  [{::keys [document handlers]}]
  (let [not-implemented (constantly {:status 503})
        routes (into {}
                 (map (fn [[k vs]]
                        [k (mapv second vs)]))
                 (group-by first
                   (for [[path $path-item] (get document "paths")
                         :let [path-item ($deref document $path-item)]
                         [method $operation] (select-keys path-item http-methods)
                         :let [operation ($deref document $operation)
                               $ref (path->json-pointer ["paths" path method])
                               handler-id (or (get operation "operationId")
                                            $ref)
                               handler (get handlers handler-id not-implemented)
                               params-names (mapv keyword (path-params-names path))]]
                     [(keyword method) (merge {::path-re   (re-pattern (path-regex path))
                                               ::handler   handler
                                               ::operation operation}
                                         (when (seq params-names)
                                           {::params-names params-names}))])))
        not-found (constantly {:status 404})]
    (fn [{:keys [uri request-method]
          :as   ring-request}]
      (let [{::keys [operation path-params handler]
             :or    {handler not-found}} (some (partial match-route? uri)
                                           (get routes request-method))
            {:keys [content]
             :as   ring-response} (handler (merge ring-request
                                             (when operation
                                               {::operation operation})
                                             (when path-params
                                               {::path-params path-params})))]
        (merge (select-keys ring-response [:status :headers :body])
          (when (contains? ring-response :content)
            {:body (reify rcp/StreamableResponseBody
                     (write-body-to-stream [body response output-stream]
                       (with-open [w (io/writer output-stream)]
                         (json/write content w))))}))))))

(s/fdef ->ring-handler
  :args (s/cat :args (s/keys :req [::document
                                   ::handlers]))
  :ret fn?)
