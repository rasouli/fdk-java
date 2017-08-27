package com.fnproject.fn.testing.cloudthreads;

import com.fnproject.fn.api.Headers;
import com.fnproject.fn.api.cloudthreads.CloudCompletionException;
import com.fnproject.fn.api.cloudthreads.HttpMethod;
import com.fnproject.fn.api.cloudthreads.LambdaSerializationException;
import com.fnproject.fn.api.cloudthreads.PlatformException;
import com.fnproject.fn.runtime.cloudthreads.CompleterClient;
import com.fnproject.fn.runtime.cloudthreads.CompletionId;
import com.fnproject.fn.runtime.cloudthreads.TestSupport;
import com.fnproject.fn.runtime.cloudthreads.ThreadId;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * In memory completer
 * Created on 25/08/2017.
 * <p>
 * (c) 2017 Oracle Corporation
 */
class InMemCompleter implements CompleterClient {
    private final Map<ThreadId, Graph> graphs = new ConcurrentHashMap<>();
    private final AtomicInteger threadCount = new AtomicInteger();
    private final CompleterInvokeClient completerInvokeClient;
    private final FnInvokeClient fnInvokeClient;

    private static ScheduledThreadPoolExecutor spe = new ScheduledThreadPoolExecutor(1);
    private static ExecutorService faasExectuor = Executors.newCachedThreadPool();

    InMemCompleter(CompleterInvokeClient completerInvokeClient, FnInvokeClient fnInvokeClient) {
        this.completerInvokeClient = completerInvokeClient;
        this.fnInvokeClient = fnInvokeClient;
    }


    private ExternalCompletionServer externalCompletionServer = new ExternalCompletionServer();

    private static class ExternalCompletionServer {
        private static final int port = 11979;
        private static final String baseUrl = "/completions/";
        Pattern pathPattern = Pattern.compile("([^/]+)/(.*)");
        HttpServer server;

        Map<String, CompletableFuture<Result>> knownCompletions = new ConcurrentHashMap<>();


        private synchronized void ensureStopped() {
            if (server != null) {
                server.stop(0);
                server = null;
            }
        }

        private synchronized ExternalCompletionServer ensureStarted() {
            if (server != null) {
                return this;
            }
            try {
                server = HttpServer.create(new InetSocketAddress(port), 0);
            } catch (IOException e) {
                throw new PlatformException("Failed to create external completer server on port " + port);
            }
            server.createContext(baseUrl, (t) -> {
                URI uri = t.getRequestURI();
                String path = uri.getPath().substring(baseUrl.length());
                Matcher match = pathPattern.matcher(path);
                if (match.matches()) {
                    String action = match.group(2);
                    String id = match.group(1);

                    CompletableFuture<Result> completableFuture = knownCompletions.get(id);
                    if (null == completableFuture) {
                        t.sendResponseHeaders(404, 0);
                        t.close();
                        return;
                    }

                    boolean success;
                    switch (action) {
                        case "complete":
                            success = true;
                            break;
                        case "fail":
                            success = false;

                            break;
                        default:
                            t.sendResponseHeaders(404, 0);
                            t.close();
                            return;
                    }

                    Map<String, String> headers = new HashMap<>();
                    for (Map.Entry<String, List<String>> e : t.getRequestHeaders().entrySet()) {
                        headers.put(e.getKey(), e.getValue().stream().collect(Collectors.joining(";")));
                    }


                    byte[] body = IOUtils.toByteArray(t.getRequestBody());
                    HttpMethod method = HttpMethod.valueOf(t.getRequestMethod().toUpperCase());

                    Datum.HttpReqDatum datum = new Datum.HttpReqDatum(method, Headers.fromMap(headers), body);
                    if (success) {
                        completableFuture.complete(Result.success(datum));
                    } else {
                        completableFuture.completeExceptionally(new ResultException(datum));
                    }
                    t.sendResponseHeaders(200, 0);
                    t.close();
                } else {
                    t.sendResponseHeaders(404, 0);
                    t.close();
                }
            });
            server.start();
            return this;
        }

        private ExternalCompletion createCompletion(ThreadId tid, CompletionId cid, CompletableFuture<Result> resultFuture) {
            ensureStarted();
            String path = tid.getId() + "_" + TestSupport.completionIdString(cid);

            knownCompletions.put(path, resultFuture);
            return new ExternalCompletion() {
                @Override
                public CompletionId completionId() {
                    return cid;
                }

                @Override
                public URI completeURI() {
                    return URI.create("http://localhost:" + port + baseUrl + path + "/complete");
                }

                @Override
                public URI failureURI() {
                    return URI.create("http://localhost:" + port + baseUrl + path + "/fail");
                }
            };
        }
    }

    @Override
    public ThreadId createThread(String functionId) {
        ThreadId id = TestSupport.threadId("thread-" + threadCount.incrementAndGet());
        graphs.put(id, new Graph(functionId));

        return id;
    }

    @Override
    public CompletionId supply(ThreadId threadID, Serializable code) {
        return withGraph(threadID, graph -> graph.addSupplyNode(serializeClosure(code))).getId();
    }

    private Datum.Blob serializeClosure(Serializable code) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(code);
            oos.close();
            return new Datum.Blob("application/x-java-serialized-object", bos.toByteArray());
        } catch (Exception e) {
            throw new LambdaSerializationException("Error serializing closure ");
        }
    }

    private <T> T withGraph(ThreadId t, Function<Graph, T> act) {
        Graph g = graphs.get(t);
        if (g == null) {
            throw new PlatformException("unknown graph " + t.getId());
        }
        return act.apply(g);
    }

    @Override
    public CompletionId thenApply(ThreadId threadID, CompletionId completionId, Serializable code) {
        return withGraph(threadID,
                (graph) -> graph.withNode(completionId,
                        (parent) -> parent.addThenApplyNode(serializeClosure(code)))).getId();
    }

    @Override
    public CompletionId whenComplete(ThreadId threadID, CompletionId completionId, Serializable code) {
        return withGraph(threadID,
                (graph) -> graph.withNode(completionId,
                        (parent) -> parent.addWhenCompleteNode(serializeClosure(code)))).getId();

    }

    @Override
    public CompletionId thenCompose(ThreadId threadId, CompletionId completionId, Serializable code) {
        return withGraph(threadId,
                (graph) -> graph.withNode(completionId,
                        (parent) -> parent.addThenComposeNode(serializeClosure(code)))).getId();
    }

    @Override
    public Object waitForCompletion(ThreadId threadId, CompletionId completionId) {
        return withGraph(threadId, (graph) -> graph.withNode(completionId, (node) -> {
            try {
                return node.outputFuture().toCompletableFuture().get().toJavaObject();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof ResultException) {

                    Result r = ((ResultException) e.getCause()).toResult();
                    Object err = r.toJavaObject();
                    if (err instanceof Throwable) {
                        throw new CloudCompletionException((Throwable) err);
                    }
                    throw new PlatformException(e);
                } else {
                    throw new PlatformException(e);
                }
            } catch (Exception e) {
                throw new PlatformException(e);
            }
        }));

    }

    @Override
    public CompletionId thenAccept(ThreadId threadId, CompletionId completionId, Serializable code) {
        return withGraph(threadId,
                (graph) -> graph.withNode(completionId,
                        (parent) -> parent.addThenAcceptNode(serializeClosure(code)))).getId();
    }

    @Override
    public CompletionId thenRun(ThreadId threadId, CompletionId completionId, Serializable code) {
        return withGraph(threadId,
                (graph) -> graph.withNode(completionId,
                        (parent) -> parent.addThenRunNode(serializeClosure(code)))).getId();

    }

    @Override
    public CompletionId acceptEither(ThreadId threadId, CompletionId completionId, CompletionId alternate, Serializable code) {
        return withGraph(threadId,
                (graph) ->
                        graph.withNode(alternate,
                                (other) ->
                                        graph.appendChildNode(completionId,
                                                (parent) -> parent.addAcceptEitherNode(other, serializeClosure(code))).getId()));

    }

    @Override
    public CompletionId applyToEither(ThreadId threadId, CompletionId completionId, CompletionId alternate, Serializable code) {
        return withGraph(threadId,
                (graph) ->
                        graph.withNode(alternate,
                                (other) ->
                                        graph.withNode(completionId,
                                                (parent) -> parent.addApplyToEitherNode(other, serializeClosure(code))).getId()));
    }

    @Override
    public CompletionId anyOf(ThreadId threadId, List<CompletionId> cids) {
        return withGraph(threadId,
                (graph) ->
                        graph.withNodes(cids, graph::addAnyOf).getId());

    }

    @Override
    public CompletionId delay(ThreadId threadId, long l) {
        return withGraph(threadId,
                (graph) -> graph.addDelayNode(l)).getId();

    }


    @Override
    public CompletionId thenAcceptBoth(ThreadId threadId, CompletionId completionId, CompletionId alternate, Serializable code) {
        return withGraph(threadId,
                (graph) ->
                        graph.withNode(alternate,
                                (other) ->
                                        graph.withNode(completionId,
                                                (parent) -> parent.addThenAcceptBothNode(other, serializeClosure(code))).getId()));

    }

    @Override
    public ExternalCompletion createExternalCompletion(ThreadId threadId) {
        CompletableFuture<Result> resultFuture = new CompletableFuture<>();

        Graph.Node node = withGraph(threadId,
                graph -> graph.addExternalNode(resultFuture));
        return externalCompletionServer.ensureStarted().createCompletion(threadId, node.id, resultFuture);
    }

    @Override
    public CompletionId invokeFunction(ThreadId threadId, String functionId, byte[] data, HttpMethod method, Headers headers) {
        return withGraph(threadId, (graph) ->
                graph.addInvokeFunction(functionId, method, headers, data)).getId();
    }

    @Override
    public CompletionId completedValue(ThreadId threadId, Serializable value) {
        return withGraph(threadId, (graph) ->
                graph.addCompletedValue(new Datum.BlobDatum(serializeClosure(value)))).getId();


    }

    @Override
    public CompletionId allOf(ThreadId threadId, List<CompletionId> cids) {
        return withGraph(threadId,
                (graph) ->
                        graph.withNodes(cids, graph::addAllOf).getId());
    }

    @Override
    public CompletionId handle(ThreadId threadId, CompletionId completionId, Serializable code) {
        return withGraph(threadId,
                (graph) ->
                        graph.withNode(completionId,
                                (node) -> node.addHandleNode(serializeClosure(code))).getId());
    }

    @Override
    public CompletionId exceptionally(ThreadId threadId, CompletionId completionId, Serializable code) {
        return withGraph(threadId,
                (graph) ->
                        graph.withNode(completionId,
                                (node) -> node.addExceptionallyNode(serializeClosure(code))).getId());
    }

    @Override
    public CompletionId thenCombine(ThreadId threadId, CompletionId completionId, Serializable code, CompletionId alternate) {
        return withGraph(threadId,
                (graph) ->
                        graph.withNode(alternate,
                                (other) ->
                                        graph.appendChildNode(completionId,
                                                (parent) -> parent.addThenCombineNode(other, serializeClosure(code))).getId()));

    }

    @Override
    public void commit(ThreadId threadId) {
        withGraph(threadId, Graph::commit);
    }


    class Graph {
        private final String functionId;
        private final AtomicBoolean committed = new AtomicBoolean(false);
        private final AtomicInteger nodeCount = new AtomicInteger();
        private final AtomicInteger activeCount = new AtomicInteger();
        private final Map<CompletionId, Node> nodes = new ConcurrentHashMap<>();


        Graph(String functionId) {
            this.functionId = functionId;
        }

        private boolean commit() {
            return committed.compareAndSet(false, true);
        }

        private Optional<Node> findNode(CompletionId ref) {
            return Optional.ofNullable(nodes.get(ref));
        }

        private Node addNode(Node node) {
            nodes.put(node.getId(), node);
            return node;
        }

        private CompletionId newNodeId() {
            return TestSupport.completionId("" + nodeCount.incrementAndGet());
        }


        private Node appendChildNode(CompletionId cid, Function<Node, Node> ctor) {
            Node newNode = withNode(cid, ctor);
            nodes.put(newNode.getId(), newNode);
            return newNode;
        }

        private <T> T withNode(CompletionId cid, Function<Node, T> function) {
            Node node = nodes.get(cid);
            if (node == null) {
                throw new PlatformException("Node not found in graph :" + cid);
            }
            return function.apply(node);
        }

        private <T> T withNodes(List<CompletionId> cids, Function<List<Node>, T> function) {
            List<Node> nodes = new ArrayList<>();
            for (CompletionId cid : cids) {
                Node node = this.nodes.get(cid);
                if (node == null) {
                    throw new PlatformException("Node not  found in graph :" + cid);
                }
                nodes.add(node);
            }
            return function.apply(nodes);
        }

        private Graph.Node addCompletedValue(Datum value) {
            CompletableFuture<Result> future = CompletableFuture.completedFuture(Result.success(value));
            return addNode(new Graph.Node(CompletableFuture.completedFuture(Collections.emptyList()),
                    (x, f) -> future));
        }

        private Graph.Node addAllOf(List<Graph.Node> cns) {
            List<CompletableFuture<Result>> outputs = cns.stream().map(Graph.Node::outputFuture).map(CompletionStage::toCompletableFuture).collect(Collectors.toList());

            CompletionStage<Result> output = CompletableFuture
                    .allOf(outputs.toArray(new CompletableFuture<?>[outputs.size()]))
                    .thenApply((nv) -> Result.success(new Datum.EmptyDatum()));

            return addNode(new Graph.Node(
                    CompletableFuture.completedFuture(Collections.emptyList()),
                    (n, f) -> output));

        }

        private Graph.Node addAnyOf(List<Graph.Node> cns) {
            List<CompletableFuture<Result>> outputs = cns.stream().map(Graph.Node::outputFuture).map(CompletionStage::toCompletableFuture).collect(Collectors.toList());

            CompletionStage<Result> output = CompletableFuture
                    .anyOf(outputs.toArray(new CompletableFuture<?>[outputs.size()])).thenApply((s) -> (Result) s);

            return addNode(new Graph.Node(CompletableFuture.completedFuture(Collections.emptyList()),
                    (n, x) -> output
            ));
        }

        private Node addSupplyNode(Datum.Blob closure) {
            CompletableFuture<List<Result>> input = CompletableFuture.completedFuture(Collections.emptyList());
            return addNode(new Node(input, chainInvocation(closure)));
        }


        private Node addExternalNode(CompletableFuture<Result> future) {
            return addNode(new Node(CompletableFuture.completedFuture(Collections.emptyList()),
                    (n, v) -> future));
        }

        private Node addDelayNode(long delay) {
            CompletableFuture<Result> future = new CompletableFuture<>();

            spe.schedule(() -> future.complete(Result.success(new Datum.EmptyDatum())), delay, TimeUnit.MILLISECONDS);

            return addNode(new Node(CompletableFuture.completedFuture(Collections.emptyList()),
                    (n, v) -> future));
        }

        private Node addInvokeFunction(String functionId, HttpMethod method, Headers headers, byte[] data) {
            return addNode(new Node(CompletableFuture.completedFuture(Collections.emptyList()),
                    (n, in) -> in.thenComposeAsync((ignored) ->
                            fnInvokeClient.invokeFunction(functionId, method, headers, data), faasExectuor)));
        }

        private BiFunction<Node, CompletionStage<List<Result>>, CompletionStage<Result>> chainInvocation(Datum.Blob closure) {
            return (node, trigger) -> trigger.thenComposeAsync((input) ->
                    completerInvokeClient.invokeStage(functionId, node.id, closure, input), faasExectuor);
        }

        private final class Node {
            private final CompletionId id;
            private final CompletionStage<Result> outputFuture;

            private Node(CompletionStage<List<Result>> input,
                         BiFunction<Node, CompletionStage<List<Result>>, CompletionStage<Result>> invoke) {

                this.id = newNodeId();
                input.whenComplete((in, err) -> activeCount.incrementAndGet());

                this.outputFuture = invoke.apply(this, input);
                outputFuture.whenComplete((in, err) -> activeCount.decrementAndGet());
            }

            private CompletionStage<Result> outputFuture() {
                return outputFuture;
            }

            private CompletionId getId() {
                return id;
            }

            private Node addThenApplyNode(Datum.Blob closure) {
                return addNode(new Node(
                        outputFuture().thenApply(Collections::singletonList),
                        chainInvocation(closure)
                ));
            }

            private Node addThenAcceptNode(Datum.Blob closure) {
                return addNode(new Node(
                        outputFuture().thenApply(Collections::singletonList),
                        chainInvocation(closure)
                ));
            }

            private Node addThenRunNode(Datum.Blob closure) {
                return addNode(new Node(
                        outputFuture().thenApply(Collections::singletonList),
                        chainInvocation(closure)
                ));
            }

            private Node addThenComposeNode(Datum.Blob closure) {
                BiFunction<Node, CompletionStage<List<Result>>, CompletionStage<Result>> invokefn =
                        chainInvocation(closure)
                                .andThen((resultStage) -> resultStage.thenCompose(result -> {
                                    if (result.getDatum() instanceof Datum.StageRefDatum) {
                                        String ref = ((Datum.StageRefDatum) result.getDatum()).getStageId();

                                        Node node = findNode(TestSupport.completionId(ref)).orElseThrow(() ->
                                                new ResultException(new Datum.ErrorDatum(Datum.ErrorType.invalid_stage_response, "returned stage not found")));
                                        return node.outputFuture;
                                    } else {
                                        throw new ResultException(new Datum.ErrorDatum(Datum.ErrorType.invalid_stage_response, "Result was not a stageref datum"));
                                    }
                                }));

                return addNode(new Node(outputFuture().thenApply(Collections::singletonList), invokefn
                ));
            }


            private List<Result> resultOrError(Result input, Throwable err) {
                if (err != null) {
                    return Arrays.asList(Result.success(new Datum.EmptyDatum()), errorToResult(err));
                } else {
                    return Arrays.asList(input, Result.success(new Datum.EmptyDatum()));
                }
            }

            private Result errorToResult(Throwable err) {
                if (err instanceof ResultException) {
                    return ((ResultException) err).toResult();
                } else {
                    System.err.println("Unexpected error " + err.toString());
                    err.printStackTrace();
                    return Result.failure(new Datum.ErrorDatum(Datum.ErrorType.unknown_error, "Unexpected error" + err.getMessage()));
                }
            }

            private Node addWhenCompleteNode(Datum.Blob closure) {
                return addNode(new Node(outputFuture().handle(this::resultOrError)
                        , chainInvocation(closure).andThen(c -> outputFuture())
                ));
            }

            private Node addHandleNode(Datum.Blob closure) {
                return addNode(new Node(outputFuture().handle(this::resultOrError)
                        , chainInvocation(closure)
                ));
            }

            private Node addAcceptEitherNode(Node otherNode, Datum.Blob closure) {
                return addNode(new Node(
                        outputFuture().applyToEither(otherNode.outputFuture, Function.identity())
                                .thenApply(Collections::singletonList),
                        chainInvocation(closure)
                                .andThen(c -> c.thenApply(Result::toEmpty))
                ));
            }

            private Node addApplyToEitherNode(Node otherNode, Datum.Blob closure) {
                return addNode(new Node(
                        outputFuture().applyToEither(otherNode.outputFuture, Collections::singletonList),
                        chainInvocation(closure)
                ));
            }

            private Node addThenAcceptBothNode(Node otherNode, Datum.Blob closure) {
                return addNode(new Node(outputFuture()
                        .thenCombine(otherNode.outputFuture,
                                (input1, input2) -> Arrays.asList(input1, input2)),
                        chainInvocation(closure)
                                .andThen(c -> c.thenApply(Result::toEmpty))
                ));

            }

            private Node addExceptionallyNode(Datum.Blob closure) {
                return addNode(new Node(outputFuture().thenApply(Collections::singletonList),
                        (node, inputs) -> {
                            CompletableFuture<Result> result = new CompletableFuture<>();
                            inputs.whenComplete((results, err) -> {
                                if (err != null) {
                                    if (err instanceof ResultException) {
                                        chainInvocation(closure).apply(node, CompletableFuture.completedFuture(Collections.singletonList(((ResultException) err).toResult())))
                                                .whenComplete((r, e) -> {
                                                    if (e != null) {
                                                        result.completeExceptionally(err);
                                                    } else {
                                                        result.complete(r);
                                                    }
                                                });
                                    } else {
                                        result.completeExceptionally(new ResultException(new Datum.ErrorDatum(Datum.ErrorType.unknown_error, "Unexpected error" + err.getMessage())));
                                    }
                                } else {
                                    result.complete(results.get(0));
                                }
                            });
                            return result;
                        }));
            }

            private Node addThenCombineNode(Node otherNode, Datum.Blob closure) {
                return addNode(new Node(outputFuture()
                        .thenCombine(otherNode.outputFuture,
                                (input1, input2) -> Arrays.asList(input1, input2)),
                        chainInvocation(closure)
                ));
            }
        }
    }
}