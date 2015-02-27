package com.emc.mongoose.util.persist;
//
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import java.io.Serializable;
//
import static javax.persistence.GenerationType.IDENTITY;
//
/**
 * Created by olga on 16.10.14.
 */
@Entity(name="mode")
public final class Mode
	implements Serializable {
	@Id
	@GeneratedValue(strategy = IDENTITY)
	@Column(name = "id")
	private long id;
	@Column(name = "name", unique = true)
	private String name;
	//
	public Mode(){
	}
	public Mode(final String name){
		this.name = name;
	}
	//
	public final long getId() {
		return id;
	}
	public final void setId(final long id) {
		this.id = id;
	}
	public final String getName() {
		return name;
	}
	public final void setName(final String name) {
		this.name = name;
	}
}