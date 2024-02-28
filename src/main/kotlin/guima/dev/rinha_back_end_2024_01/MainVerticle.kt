package guima.dev.rinha_back_end_2024_01

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.Tuple
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter


class MainVerticle : AbstractVerticle() {

  private lateinit var dbClient: Pool

  companion object {
    var clientsAccountLimits: HashMap<Int, Int> = hashMapOf(
      1 to 100000,
      2 to 80000,
      3 to 1000000,
      4 to 10000000,
      5 to 500000
    )
  }

  override fun start(startPromise: Promise<Void>) {
    val pgConnectOptions = PgConnectOptions()
      .setPort(5432)
      .setHost(System.getenv("DB_HOST") ?: "localhost")
      .setDatabase("rinha")
      .setUser("root")
      .setPassword("rinha")
    val poolOptions = PoolOptions().setMaxSize(4)

    dbClient = Pool.pool(vertx, pgConnectOptions, poolOptions)

    val router = Router.router(vertx)
      .also {
        it.route().handler(BodyHandler.create())
        it.post("/clientes/:id/transacoes").handler(this::createTransaction)
        it.get("/clientes/:id/extrato").handler(this::getTransactions)
      }

    vertx
      .createHttpServer()
      .requestHandler(router)
      .listen(8888) { http ->
        if (http.succeeded()) {
          startPromise.complete()
          println("HTTP server started on port 8888")
        } else {
          startPromise.fail(http.cause())
        }
      }
  }

  private fun createTransaction(context: RoutingContext) {
    val clientId: Int
    var amount: Int
    var type: String
    var description: String

    try {
      clientId = context.pathParam("id").toInt()
      context.body().asJsonObject()
        .also {
          val amountStr =  it.getString("valor")
          if (amountStr.contains(".")) {
            context.fail(422)
            return
          }
          amount = amountStr.toInt()
          type = it.getString("tipo")
          description = it.getString("descricao")
        }
    } catch (e: Exception) {
      context.fail(422, e)
      return
    }

    if (amount <= 0 ||
        (type != "c" && type != "d") ||
        description.trim().isEmpty() ||
        description.length > 10) {
      context.fail(422)
      return
    }

    val accountLimit = clientsAccountLimits[clientId]
    if (accountLimit == null) {
      context.fail(404)
      return
    }

    //Getting the current client data
    dbClient.withTransaction { tx ->
      tx.query("SELECT pg_advisory_xact_lock($clientId)")
        .execute()
        .compose {
          tx.preparedQuery("SELECT balance FROM transactions WHERE client_id = $1 ORDER BY id DESC LIMIT 1")
            .execute(Tuple.of(clientId))
        }
        .compose {
          var balance = 0
          if (it.size() > 0) {
            balance = it.first().getInteger("balance")
          }

          var currentBalance = 0
          if (type == "d") {
            currentBalance = balance - amount
            if (currentBalance < - accountLimit) {
              context.fail(422)
              return@compose Future.failedFuture("Insufficient funds")
            }
          } else {
            currentBalance = balance + amount
          }

          tx.preparedQuery("INSERT INTO transactions(amount, type, description, balance, client_id) VALUES($1, $2, $3, $4, $5)")
            .execute(Tuple.of(amount, type, description, currentBalance, clientId))
            .map { currentBalance }
        }
        .onSuccess { currentBalance ->
          context.json(mapOf("limite" to accountLimit, "saldo" to currentBalance))
          return@onSuccess
        }
        .onFailure {
          context.fail(422, it)
          return@onFailure
        }
    }
  }

  private fun getTransactions(context: RoutingContext) {
    val clientId: Int

    try {
      clientId = context.pathParam("id").toInt()
    } catch (e: Exception) {
      context.fail(422, e)
      return
    }

    val accountLimit = clientsAccountLimits[clientId]
    if (accountLimit == null) {
      context.fail(404)
      return
    }

    //Getting the last 10 transactions
    dbClient.preparedQuery("SELECT amount, type, description, created_at, balance FROM transactions WHERE client_id = $1 ORDER BY created_at DESC LIMIT 10")
      .execute(Tuple.of(clientId))
      .onSuccess {
        val lastTenTransactions: MutableList<Map<String, Any>> = mutableListOf()

        var currentBalance = 0
        if (it.size() > 0) {
          currentBalance = it.first().getInteger("balance")
        }
        val balanceData = mapOf(
          "total" to currentBalance,
          "data_extrato" to formatInstantToUTC(Instant.now()),
          "limite" to accountLimit
        )

        it.forEach {
          val amount = it.getInteger("amount")
          val type = it.getString("type")
          val description = it.getString("description")
          val createdAt = formatInstantToUTC(it.getLocalDateTime("created_at").toInstant(ZoneOffset.UTC))
          lastTenTransactions.addLast(mapOf(
            "valor" to amount,
            "tipo" to type,
            "descricao" to description,
            "realizada_em" to createdAt
          ))
        }

        context.json(mapOf(
          "saldo" to balanceData,
          "ultimas_transacoes" to lastTenTransactions
        ))
        return@onSuccess
      }
      .onFailure {
        context.fail(422)
        return@onFailure
      }
  }

  private fun formatInstantToUTC(instant: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
      .withZone(ZoneOffset.UTC)
    return formatter.format(instant)
  }
}
