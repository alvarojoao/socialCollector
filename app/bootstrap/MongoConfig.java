package bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.AbstractMongoConfiguration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

import models.BeforeSaveListener;
import play.Play;

import com.mongodb.Mongo;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;

/**
 * Mongodb Configuration - Heroku
 * @author Alvaro
 *
 */
@Configuration
@EnableMongoAuditing
public class MongoConfig extends AbstractMongoConfiguration{

	private static Mongo mongo;
    private static MongoClientURI clientURI;
	
	public static void disconnect(){
		if(mongo != null){
			mongo.close();
		}
	}

	@Override
	protected String getDatabaseName() {
        return clientURI.getDatabase();
	}

    @Bean
    public BeforeSaveListener beforeSaveListener() {
        return new BeforeSaveListener();
    }

	@Override
	@Bean
	public Mongo mongo() throws Exception {
		
		String MONGO_URI = Play.application().configuration().getString("mongo_uri");

        clientURI = new MongoClientURI(MONGO_URI);
		mongo = new MongoClient(clientURI);
		return mongo;
	}
}