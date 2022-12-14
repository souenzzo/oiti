(ns br.dev.zz.oiti-test
  (:refer-clojure :exclude [send])
  (:require
    [br.dev.zz.oiti :as oiti]
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is]]
    [ring.core.protocols :as rcp])
  (:import (java.io ByteArrayOutputStream)))

(set! *warn-on-reflection* true)

(comment
  (json/read (io/reader "https://raw.githubusercontent.com/OAI/OpenAPI-Specification/3.1.0/examples/v3.0/petstore.json")))

(defn paths->openapi
  [paths]
  (-> {:openapi "3.0.0"
       :info    {:title   "hello"
                 :version "1.0.0"}
       :paths   paths}
    json/write-str
    .getBytes
    oiti/load))

(defn send
  [handler ring-request]
  (let [{:keys [status headers body]
         :as   http-response} (handler ring-request)]
    (merge {:status status}
      (when headers
        {:headers headers})
      (when body
        (let [output-stream (ByteArrayOutputStream.)]
          (with-open [output-stream output-stream]
            (rcp/write-body-to-stream body http-response output-stream))
          (with-open [rdr (io/reader (.toByteArray output-stream))]
            {:content (json/read rdr :key-fn keyword)}))))))

(deftest hello
  (let [response-schema {:type       :object
                         :properties {:hello {:type :string}}}
        default-response {:description "ok"
                          :content     {"application/json" {:schema response-schema}}}
        document (paths->openapi {"/hello" {:get {:responses {:default default-response}}}})
        handler (-> {::oiti/document document
                     ::oiti/handlers {"#/paths/~1hello/get" (fn [req]
                                                              {:content {:hello "world"}
                                                               :status  200})}}
                  oiti/->ring-handler)]
    (is (= {:content {:hello "world"}
            :status  200}
          (-> (send handler {:uri            "/hello"
                             :headers        {"Accept" "application/json"}
                             :request-method :get})
            #_(doto clojure.pprint/pprint))))
    (is (= {:status 404}
          (-> (send handler {:uri            "/world"
                             :headers        {"Accept" "application/json"}
                             :request-method :get})
            #_(doto clojure.pprint/pprint))))))


(deftest hello-with-path-params
  (let [response-schema {:type       :object
                         :properties {:hello {:type :string}}}
        default-response {:description "ok"
                          :content     {"application/json" {:schema response-schema}}}
        document (paths->openapi {"/hello/{who}" {:get {:operationId "hello"
                                                        :responses   {:default default-response}}}})
        handler (-> {::oiti/document document
                     ::oiti/handlers {"hello" (fn [{::oiti/keys [path-params]}]
                                                {:content {:hello (get path-params "who")}
                                                 :status  200})}}
                  oiti/->ring-handler)]
    (is (= {:content {:hello "world's"}
            :status  200}
          (-> (send handler {:uri            "/hello/world's"
                             :headers        {"Accept" "application/json"}
                             :request-method :get})
            #_(doto clojure.pprint/pprint))))))


(deftest path-regex
  (is (= "/foo"
        (oiti/path-regex "/foo")))
  (is (= "/foo/([^/]+)/car"
        (oiti/path-regex "/foo/{bar}/car")))
  (is (= "/foo/([^/]+)/car/([^/]+)"
        (oiti/path-regex "/foo/{bar}/car/{tar}"))))

(deftest path-params-names
  (is (empty? (oiti/path-params-names "/foo")))
  (is (= ["bar"]
        (oiti/path-params-names "/foo/{bar}/car")))
  (is (= ["bar" "tar"]
        (oiti/path-params-names "/foo/{bar}/car/{tar}"))))

(deftest json-pointer
  (is (= "#/paths/~1hello/get"
        (oiti/path->json-pointer ["paths" "/hello" "get"])))
  (is (= ["paths" "/hello" "get"]
        (oiti/json-pointer->path "#/paths/~1hello/get"))))

(deftest oiti-deref
  (is (= {"a" "b"
          "d" "e"
          "g" "h"}
        (oiti/$deref {"c" {"d"    "e"
                           "$ref" "#/f"}
                      "f" {"g" "h"}}
          {"a"    "b"
           "$ref" "#/c"}))))
(defonce *petstore-openapi-v3
  (delay
    (-> "https://raw.githubusercontent.com/OAI/OpenAPI-Specification/3.1.0/examples/v3.0/petstore.json"
      io/input-stream
      oiti/load)))
(deftest petstore-simple
  (let [handler (-> {::oiti/document @*petstore-openapi-v3
                     ::oiti/handlers {"listPets" (fn [{::oiti/keys [query-params]}]
                                                   (let [;; todo: parse query and coerce to int
                                                         limit (:limit query-params)]
                                                     {:headers {"x-next" ""}
                                                      :content (cond->> [{:id   1
                                                                          :name "dog"
                                                                          :tag  "first"}
                                                                         {:id   2
                                                                          :name "dog"}
                                                                         {:id   3
                                                                          :name "dog"}
                                                                         {:id   4
                                                                          :name "dog"
                                                                          :tag  "last"}]
                                                                 limit (take limit))
                                                      :status  200}))}}
                  oiti/->ring-handler)]
    (is (= {:content [{:id   1
                       :name "dog"
                       :tag  "first"}
                      {:id   2
                       :name "dog"}
                      {:id   3
                       :name "dog"}
                      ;; todo: should no be here
                      {:id   4
                       :name "dog"
                       :tag  "last"}]
            :headers {"x-next" ""}
            :status  200}
          (-> (send handler {:uri            "/pets"
                             :query-string   "limit=3"
                             :headers        {"Accept" "application/json"}
                             :request-method :get})
            #_(doto clojure.pprint/pprint))))))
