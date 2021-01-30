package net.sansa_stack.spark.cli.cmd;

import net.sansa_stack.spark.cli.cmd.impl.CmdSansaTrigQueryImpl;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;
@Command(name = "trig",
        description = "Run a special SPARQL query on a trig file")
public class CmdSansaTrigQuery
    implements Callable<Integer>
{
    @Option(names = { "-m", "--spark-master" }, defaultValue = "local[*]")
    public String sparkMaster;

    @Option(names = { "-o", "--out-format" }, description = "Output format")
    public String outFormat = null;

    @Option(names = { "--rq" }, description = "File with a SPARQL query (RDF Query)")
    public String queryFile = null;

    @Parameters(arity = "1", description = "Trig File")
    public String trigFile;


    @Override
    public Integer call() throws Exception {
        CmdSansaTrigQueryImpl test = new CmdSansaTrigQueryImpl();
        System.err.println("Created instance: " + test);
        return CmdSansaTrigQueryImpl.run(this);
    }
}
