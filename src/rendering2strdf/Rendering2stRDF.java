package rendering2strdf;

import util.DB;
import util.OHDM_DB;
import util.Parameter;
import util.SQLStatementQueue;

import java.io.*;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;

import static java.lang.System.out;

public class Rendering2stRDF {
    private final Parameter sourceParameter;
    private final SQLStatementQueue sqlQueue;
    private int lfd;
    private final BufferedWriter out;


    public Rendering2stRDF(Parameter sourceParameter, String filename)
            throws IOException, SQLException {
        this.sourceParameter = sourceParameter;

        this.sqlQueue = new SQLStatementQueue(this.sourceParameter);

        this.out = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(filename), "UTF-8"));

        // write preamble
        this.out.write("@prefix ohdm: <http://www.ohdm.net/ohdm/schema/1.0> . \n");
        this.out.write("@prefix xsd: <http://www.w3.org/2001/XMLSchema#> . \n");
        this.out.write("@prefix strdf: <http://strdf.di.uoa.gr/ontology#> . \n");

        this.lfd = 0;
    }

    void produceTurtle(String tableName, int geomType) throws SQLException, IOException {
        /*
        select object_id, geom_id,
classid, name, valid_since, valid_until
from landuse_gardeningandfarm;
         */

        this.sqlQueue.append("SELECT object_id, geom_id, classid, name, valid_since, valid_until, st_astext(");
        switch(geomType) {
            case OHDM_DB.OHDM_POINT_GEOMTYPE: this.sqlQueue.append("point"); break;
            case OHDM_DB.OHDM_LINESTRING_GEOMTYPE: this.sqlQueue.append("line"); break;
            case OHDM_DB.OHDM_POLYGON_GEOMTYPE: this.sqlQueue.append("polygon"); break;
        }

        this.sqlQueue.append(") FROM ");
        this.sqlQueue.append(DB.getFullTableName(this.sourceParameter.getSchema(), tableName));
        this.sqlQueue.append(";");

        ResultSet qResult = this.sqlQueue.executeWithResult();

        while(qResult.next()) {
            this.writeTurtleEntry(
                    qResult.getBigDecimal(1),
                    qResult.getBigDecimal(2),
                    qResult.getBigDecimal(3),
                    qResult.getString(4),
                    qResult.getDate(5),
                    qResult.getDate(6),
                    qResult.getString(7));
        }
    }

    private void writeTurtleEntry(BigDecimal objectID, BigDecimal geomID, BigDecimal classificationID,
                                    String name, Date since, Date until, String wkt) throws IOException {

        StringBuilder b = new StringBuilder(); // good for debugging

        /*
        template:
        _:ID1 ohdm:objectid "42"^^xsd:integer ;
            ohdm:geometryid "42"^^xsd:integer ;
            ohdm:classid "42"^^xsd:integer ;
            ohdm:validsince "2001-01-01"^^xsd:date ;
            ohdm:validuntil "2010-01-01"^^xsd:date ;
            strdf:hasGeometry "LINESTRING(13.5 52.4, 13.6 52.5);<http://www.opengis.net/def/crs/EPSG/0/4326>"^^strdf:WKT ;
            ohdm:name "AName" .
         */

        b.append("ID");
        b.append(Integer.toString(this.lfd++));

        // objectid
        b.append(" ohdm:objectid \"");
        b.append(objectID.toString());
        b.append(" \"^^xsd:integer ;");

        // geomid
        b.append(" ohdm:geometryid \"");
        b.append(geomID.toString());
        b.append(" \"^^xsd:integer ;");

        // classid
        b.append(" ohdm:classificationID \"");
        b.append(geomID.toString());
        b.append(" \"^^xsd:integer ;");

        // validsince
        b.append(" ohdm:validsince \"");
        b.append(since.toString()); // sql.Date.toString produces "yyyy-mm-tt" not util.Date!!!
        b.append(" \"^^xsd:date ;");

        // validsince
        b.append(" ohdm:validuntil \"");
        b.append(until.toString()); // sql.Date.toString produces "yyyy-mm-tt" not util.Date!!!
        b.append(" \"^^xsd:date ;");

        // wkt string
        b.append(" strdf:hasGeometry \"");
        b.append(wkt);
        b.append(";<http://www.opengis.net/def/crs/EPSG/0/4326>\"^^strdf:WKT");

        if(name != null) {
            // name
            b.append(" ; ohdm:name \"");
            b.append(name);
            b.append("\"");
        }

        b.append(" .");

        this.out.write(b.toString());
    }
}
