package org.neo4j.examples.socnet;

import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.helpers.collection.IteratorUtil;
import org.neo4j.index.IndexService;
import org.neo4j.index.lucene.LuceneIndexService;
import org.neo4j.kernel.EmbeddedGraphDatabase;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Random;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.collection.IsCollectionContaining.hasItems;
import static org.junit.Assert.assertThat;

public class SocnetTest
{
    private static final Random r = new Random( System.currentTimeMillis() );
    private GraphDatabaseService graphDb;
    private IndexService index;
    private PersonRepository personRepository;
    private int nrOfPersons;

    @Before
    public void setup()
    {
        graphDb = new EmbeddedGraphDatabase( "target/socnetdb" );
        index = new LuceneIndexService( graphDb );
        personRepository = new PersonRepository( graphDb, index );
        deleteSocialGraph();

        nrOfPersons = 20;
        createPersons();
        setupFriendsBetweenPeople( 10 );
    }

    @After
    public void teardown()
    {
        try
        {
            deleteSocialGraph();
        }
        finally
        {
            index.shutdown();
            graphDb.shutdown();
        }
    }

    @Test
    public void addStatusAndRetrieveIt() throws Exception
    {
        Person person = getRandomPerson();
        person.addStatus( "Testing!" );

        StatusUpdate update = person.getStatus().iterator().next();

        assertThat( update, CoreMatchers.<Object>notNullValue() );
        assertThat( update.getStatusText(), equalTo( "Testing!" ) );
        assertThat( update.getPerson(), equalTo( person ) );
    }

    @Test
    public void multipleStatusesComeOutInTheRightOrder() throws Exception
    {
        ArrayList<String> statuses = new ArrayList<String>();
        statuses.add( "Test1" );
        statuses.add( "Test2" );
        statuses.add( "Test3" );

        Person person = getRandomPerson();
        for ( String status : statuses )
        {
            person.addStatus( status );
        }

        int i = statuses.size();
        for ( StatusUpdate update : person.getStatus() )
        {
            i--;
            assertThat( update.getStatusText(), equalTo( statuses.get( i ) ) );
        }
    }

    @Test
    public void removingOneFriendIsHandledCleanly()
    {
        Person person1 = personRepository.getPersonByName( "person#1" );
        Person person2 = personRepository.getPersonByName( "person#2" );
        person1.addFriend( person2 );

        int noOfFriends = person1.getNrOfFriends();

        person1.removeFriend( person2 );

        int noOfFriendsAfterChange = person1.getNrOfFriends();

        assertThat( noOfFriends, equalTo( noOfFriendsAfterChange + 1 ) );
    }

    @Test
    public void retrieveStatusUpdatesInDateOrder() throws Exception
    {
        Person person = getRandomPersonWithFriends();
        int numberOfStatuses = 20;

        for ( int i = 0; i < numberOfStatuses; i++ )
        {
            Person friend = getRandomFriendOf( person );
            friend.addStatus( "Dum-deli-dum..." );
        }

        ArrayList<StatusUpdate> updates = fromIterableToArrayList( person.friendStatuses() );
        assertThat( updates.size(), equalTo( numberOfStatuses ) );
        assertUpdatesAreSortedByDate( updates );
    }

    @Test
    public void friendsOfFriendsWorks() throws Exception
    {
        Person person = getRandomPerson();
        Person friend = getRandomFriendOf( person );

        for ( Person friendOfFriend : friend.getFriends() )
        {
            if ( !friendOfFriend.equals( person ) )
            { // You can't be friends with yourself.
                assertThat( person.getFriendsOfFriends(), hasItems( friendOfFriend ) );
            }
        }
    }

    @Test
    public void getPathBetweenFriends() throws Exception
    {
        deleteSocialGraph();
        Person start = personRepository.createPerson( "start" );
        Person middleMan1 = personRepository.createPerson( "middle1" );
        Person middleMan2 = personRepository.createPerson( "middle2" );
        Person endMan = personRepository.createPerson( "endMan" );

        // Start -> middleMan1 -> middleMan2 -> endMan

        start.addFriend( middleMan1 );
        middleMan1.addFriend( middleMan2 );
        middleMan2.addFriend( endMan );

        Iterable<Person> path = start.getPersonsFromMeTo( endMan, 4 );
        assertPathIs( path, start, middleMan1, middleMan2, endMan );
    }

    @Test
    public void singleFriendRecommendation() throws Exception
    {
        deleteSocialGraph();
        Person a = personRepository.createPerson( "a" );
        Person b = personRepository.createPerson( "b" );
        Person c = personRepository.createPerson( "c" );
        Person d = personRepository.createPerson( "d" );
        Person e = personRepository.createPerson( "e" );

        // A is friends with B,C and D
        a.addFriend( b );
        a.addFriend( c );
        a.addFriend( d );

        // E is also friend with B, C and D
        e.addFriend( b );
        e.addFriend( c );
        e.addFriend( d );

        Person recommendation = IteratorUtil.singleValueOrNull( a.getFriendRecommendation( 1 ).iterator() );

        assertThat( recommendation, equalTo( e ) );
    }

    @Test
    public void weightedFriendRecommendation() throws Exception
    {
        deleteSocialGraph();
        Person a = personRepository.createPerson( "a" );
        Person b = personRepository.createPerson( "b" );
        Person c = personRepository.createPerson( "c" );
        Person d = personRepository.createPerson( "d" );
        Person e = personRepository.createPerson( "e" );
        Person f = personRepository.createPerson( "f" );


        // A is friends with B,C and D
        a.addFriend( b );
        a.addFriend( c );
        a.addFriend( d );

        // E is only friend with B
        e.addFriend( b );

        // F is friend with B, C, D
        f.addFriend( b );
        f.addFriend( c );
        f.addFriend( d );

        ArrayList<Person> recommendations = fromIterableToArrayList( a.getFriendRecommendation( 2 ).iterator() );

        assertThat( recommendations.get( 0 ), equalTo( f ));
        assertThat( recommendations.get( 1 ), equalTo( e ));
    }

    private <T> ArrayList<T> fromIterableToArrayList( Iterator<T> iterable )
    {
        ArrayList<T> collection = new ArrayList<T>();
        IteratorUtil.addToCollection( iterable, collection );
        return collection;
    }

    private void assertPathIs( Iterable<Person> path,
                                       Person... expectedPath )
    {
        ArrayList<Person> pathArray = new ArrayList<Person>();
        IteratorUtil.addToCollection( path, pathArray );
        assertThat( pathArray.size(), equalTo( expectedPath.length ) );
        for ( int i = 0; i < expectedPath.length; i++ )
        {
            assertThat( pathArray.get( i ), equalTo( expectedPath[ i ] ) );
        }
    }

    private void setupFriendsBetweenPeople( int maxNrOfFriendsEach )
    {
        for ( Person person : personRepository.getAllPersons() )
        {
            int nrOfFriends = r.nextInt( maxNrOfFriendsEach ) + 1;
            for ( int j = 0; j < nrOfFriends; j++ )
            {
                person.addFriend( getRandomPerson() );
            }
        }
    }

    private Person getRandomPerson()
    {
        return personRepository.getPersonByName( "person#"
                + r.nextInt( nrOfPersons ) );
    }

    private void deleteSocialGraph()
    {
        for ( Person person : personRepository.getAllPersons() )
        {
            personRepository.deletePerson( person );
        }
    }

    private Person getRandomFriendOf( Person p )
    {
        ArrayList<Person> friends = new ArrayList<Person>();
        IteratorUtil.addToCollection( p.getFriends().iterator(), friends );
        return friends.get( r.nextInt( friends.size() ) );
    }

    private Person getRandomPersonWithFriends()
    {
        Person p;
        do
        {
            p = getRandomPerson();
        }
        while ( p.getNrOfFriends() == 0 );
        return p;
    }

    private void createPersons()
    {
        for ( int i = 0; i < nrOfPersons; i++ )
        {
            personRepository.createPerson( "person#" + i );
        }
    }

    private void assertUpdatesAreSortedByDate(
            ArrayList<StatusUpdate> statusUpdates )
    {
        Date date = new Date( 0 );
        for ( StatusUpdate update : statusUpdates )
        {
            org.junit.Assert.assertTrue( date.getTime() < update.getDate().getTime() );
            // TODO: Should be assertThat(date, lessThan(update.getDate));
        }
    }
}
