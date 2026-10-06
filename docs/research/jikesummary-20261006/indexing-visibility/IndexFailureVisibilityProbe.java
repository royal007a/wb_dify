package com.hify.knowledge.application;

import com.hify.knowledge.domain.*;
import com.hify.knowledge.infrastructure.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Diagnostic only: production indexer, real H2 transaction, repository adapters are mocks. */
public final class IndexFailureVisibilityProbe {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:visibility-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        var db=new JdbcTemplate(ds);
        db.execute("create table observed_task(id varchar(30) primary key,state varchar(30))");
        db.execute("create table observed_doc(id varchar(30) primary key,state varchar(30),error varchar(200))");
        db.execute("create table document_chunks(id varchar(30) primary key,document_id varchar(30),document_version int,content varchar(200))");
        db.update("insert into observed_task values('task','SUCCEEDED')");
        db.update("insert into observed_doc values('doc','DONE',null)");
        db.update("insert into document_chunks values('chunk','doc',1,'previous canonical text')");
        var document=new KnowledgeDocument("doc","kb","fixture.txt","text/plain",12,"digest","replacement text",Instant.now());
        document.indexed(1);
        var task=new DocumentIndexTask("task","doc",1,Instant.now());task.succeeded();
        var documents=mock(KnowledgeDocumentRepository.class);
        var tasks=mock(DocumentIndexTaskRepository.class);
        var bases=mock(KnowledgeBaseRepository.class);
        when(documents.findByIdAndArchivedAtIsNull("doc")).thenReturn(Optional.of(document));
        when(tasks.findByDocumentIdAndDocumentVersion("doc",1)).thenReturn(Optional.of(task));
        when(bases.findByIdAndArchivedAtIsNull("kb")).thenReturn(Optional.of(new KnowledgeBase("kb","fixture","",256,16,Instant.now())));
        when(tasks.save(any(DocumentIndexTask.class))).thenAnswer(call->{
            DocumentIndexTask value=call.getArgument(0);
            db.update("update observed_task set state=? where id='task'",value.getState());return value;
        });
        when(documents.save(any(KnowledgeDocument.class))).thenAnswer(call->{
            KnowledgeDocument value=call.getArgument(0);
            db.update("update observed_doc set state=?,error=? where id='doc'",value.getIndexingState().name(),value.getErrorMessage());return value;
        });
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var injected=new java.util.concurrent.atomic.AtomicBoolean();
        var worker=Executors.newSingleThreadExecutor();
        JdbcTemplate jdbc=spy(new JdbcTemplate(ds));
        doAnswer(call->{
            ConnectionCallback<?> callback=call.getArgument(0);
            Connection bound=DataSourceUtils.getConnection(ds);
            try {
                require(DataSourceUtils.isConnectionTransactional(bound,ds),"metadata must use transaction-bound connection");
                entered.countDown();
                require(release.await(60,TimeUnit.SECONDS),"release not received");
                DatabaseMetaData metadata=(DatabaseMetaData)java.lang.reflect.Proxy.newProxyInstance(
                    DatabaseMetaData.class.getClassLoader(),new Class<?>[]{DatabaseMetaData.class},(proxy,method,parameters)->{
                        if(method.getName().equals("getDatabaseProductName")){injected.set(true);throw new SQLException("private-fixture-detail");}
                        throw new UnsupportedOperationException(method.getName());
                    });
                Connection fault=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,parameters)->{
                    if(method.getName().equals("getMetaData"))return metadata;
                    try{return method.invoke(bound,parameters);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                });
                return callback.doInConnection(fault);
            } finally {DataSourceUtils.releaseConnection(bound,ds);}
        }).when(jdbc).execute(any(ConnectionCallback.class));
        var service=new DocumentIndexingService(documents,bases,tasks,jdbc);
        ReflectionTestUtils.setField(service,"transactionManager",new DataSourceTransactionManager(ds));
        try {
            var done=worker.submit(()->service.index("doc"));
            require(entered.await(60,TimeUnit.SECONDS),"indexer not admitted");
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);String before=null;int polls=0;
            while(System.nanoTime()<end){before=db.queryForObject("select state from observed_task where id='task'",String.class);polls++;if("FAILED".equals(before))break;Thread.sleep(10);}
            require("SUCCEEDED".equals(before),"old success should remain visible before transaction completion");
            require(!injected.get(),"metadata exception must still be behind barrier");
            release.countDown();done.get(60,TimeUnit.SECONDS);
            String after=db.queryForObject("select state from observed_task where id='task'",String.class);
            require("FAILED".equals(after),"completed production indexing transaction must persist failure");
            require("FAILED".equals(db.queryForObject("select state from observed_doc where id='doc'",String.class)),"document must fail");
            require(!db.queryForObject("select error from observed_doc where id='doc'",String.class).contains("private-fixture-detail"),"private detail leaked");
            require("previous canonical text".equals(db.queryForObject("select content from document_chunks where id='chunk'",String.class)),"old chunk changed");
            System.out.println("{\"scope\":\"H2 transaction mechanism, not original PostgreSQL root-cause proof\",\"observedBeforeFailureInjected\":true,\"duringFiveSecondPoll\":\""+before+"\",\"afterWorkerTransaction\":\""+after+"\",\"pollCount\":"+polls+",\"chunksUnchanged\":true}");
        } finally {release.countDown();worker.shutdownNow();require(worker.awaitTermination(10,TimeUnit.SECONDS),"worker leaked");}
    }
}
