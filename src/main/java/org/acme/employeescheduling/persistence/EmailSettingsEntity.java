package org.acme.employeescheduling.persistence;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="email_settings")
public class EmailSettingsEntity extends PanacheEntityBase {
 @Id public Integer id=1;
 @Column(name="host",nullable=false) public String host="";
 @Column(name="port",nullable=false) public int port=587;
 @JdbcTypeCode(SqlTypes.INTEGER) @Column(name="start_tls",nullable=false) public boolean startTls=true;
 @Column(name="username",nullable=false) public String username="";
 @Column(name="password",nullable=false) public String password="";
 @Column(name="mail_from",nullable=false) public String mailFrom="";
}
