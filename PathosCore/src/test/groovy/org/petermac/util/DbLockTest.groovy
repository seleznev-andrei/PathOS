package org.petermac.util

/**
 * Created by lara luis on 22/01/2016.
 */
class DbLockTest extends GroovyTestCase
{
    static Locator loc  = Locator.instance
    String DB

    void setUp()
    {
        def env = System.getenv()

        DB = loc.pathosEnv
        assert DB != null : "[T E S T]: ${DB} does not look like a valid database"

    }
    /**
     *  Checking the state of the lock Map compare( String dbaseA, String dbaseB, List tables = [] )
     */
    void testHasLock()
    {

        dropLockTable()

        def dbc = new DbLock(DB)
        dbc.createLock()

        if(dbc.hasLock())
        {
            println "Reseting Lock ${dbc.hasLock()}, for testing..."
            assert dbc.hasLock().size() != 0 :"[T E S T]: Test has a lock"
            dbc.clearLock(dbc.hasLock())
            assert dbc.hasLock().size() == 0 :"[T E S T]: Failed to clear lock"
        }
        else
        {
            println "No Lock..."
            assert true
        }

        cleanUp()
    }

    /**
     * TESTING static Map clearLock( Map lockMap )
     */
    void testClearLock()
    {
        dropLockTable()

        def dbc = new DbLock(DB)
        dbc.createLock()
        dbc.setLock()

        def m = dbc.clearLock(dbc.hasLock())
        assert m ==[:] : "Map is not empty"
        cleanUp()

    }
    /**
     * TESTING  static Map setLock()
     */
    void testSetLock()
    {
        dropLockTable()

        def dbc = new DbLock(DB)
        dbc.createLock()

        assert dbc.setLock().keySet().size() == 4 : "The size is different form 4"

        cleanUp()
    }

    /**
     *  Clean up procedure to remove the lock & recreate a blank one
     */
    void cleanUp()
    {
        def dbc = new MysqlCommand(DB)
        dbc.run( "DROP TABLE IF EXISTS db_lock" )
        def dbl = new DbLock(DB)
        dbl.createLock()
    }


    /**
     *  Clean up procedure to drop the lock table
     */
    void dropLockTable() {
        def dbc = new MysqlCommand(DB)
        println "RMOVING DB LOCK...."
        dbc.run("DROP TABLE IF EXISTS db_lock")

    }

}
