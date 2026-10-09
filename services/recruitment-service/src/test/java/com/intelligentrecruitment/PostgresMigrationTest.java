package com.intelligentrecruitment;
import java.sql.DriverManager;
import java.nio.file.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class PostgresMigrationTest {
 @Test void upgradesPostgresThroughFlywayAndValidatesHistory() throws Exception {
   String url=System.getProperty("rd.test.jdbc-url"),user=System.getProperty("rd.test.jdbc-username"),password=System.getProperty("rd.test.jdbc-password");
   Assumptions.assumeTrue(url!=null&&user!=null&&password!=null,"An isolated PostgreSQL validation database is required");
   var config=Flyway.configure().dataSource(url,user,password);
   String target=System.getProperty("rd.test.flyway-target");
   if(target!=null)config.target(MigrationVersion.fromVersion(target));
   Flyway flyway=config.load();flyway.migrate();flyway.validate();
   if(target!=null&&Boolean.getBoolean("rd.test.flyway-upgrade")){
     flyway=Flyway.configure().dataSource(url,user,password).load();flyway.migrate();flyway.validate();
   }
   try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement();var rs=s.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")){rs.next();try(var files=Files.list(Path.of("src/main/resources/db/migration"))){assertEquals(files.filter(p->p.toString().endsWith(".sql")).count(),rs.getLong(1));}}
 }
}
