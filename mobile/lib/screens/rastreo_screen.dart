import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import '../services/app_config.dart';

class RastreoScreen extends StatefulWidget {
  const RastreoScreen({super.key});

  @override
  State<RastreoScreen> createState() => _RastreoScreenState();
}

class _RastreoScreenState extends State<RastreoScreen> {
  final TextEditingController _ticketController = TextEditingController();
  bool _buscando = false;
  Map<String, dynamic>? _denunciaEncontrada;
  String? _errorMensaje;

  @override
  void dispose() {
    _ticketController.dispose();
    super.dispose();
  }

  Future<void> _buscarTicket() async {
    final ticket = _ticketController.text.trim().toUpperCase();
    if (ticket.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Ingresa un número de ticket ej. ECO-XXXX')),
      );
      return;
    }

    setState(() {
      _buscando = true;
      _denunciaEncontrada = null;
      _errorMensaje = null;
    });

    try {
      final res = await http.get(Uri.parse(AppConfig.rastreoUrl(ticket))).timeout(
        const Duration(seconds: 10),
      );

      final data = jsonDecode(res.body);

      if (res.statusCode == 200 && data['success'] == true) {
        setState(() {
          _denunciaEncontrada = data['denuncia'];
        });
      } else {
        setState(() {
          _errorMensaje = data['error'] ?? 'No se encontró la denuncia con ticket $ticket';
        });
      }
    } catch (e) {
      setState(() {
        _errorMensaje = 'Error al consultar ticket: $e';
      });
    } finally {
      setState(() => _buscando = false);
    }
  }

  Color _obtenerColorEstado(String? estado) {
    switch ((estado ?? '').toLowerCase()) {
      case 'pendiente':
        return Colors.amber.shade800;
      case 'en_proceso':
        return Colors.blue.shade700;
      case 'cerrada':
        return const Color(0xFF10B981);
      case 'rechazada':
        return Colors.red.shade700;
      case 'requiere_revision':
        return Colors.orange.shade900;
      default:
        return Colors.grey.shade700;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF8FAFC),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Row(
                children: [
                  Icon(Icons.local_shipping_outlined, color: Color(0xFF10B981), size: 28),
                  SizedBox(width: 10),
                  Text(
                    'Seguimiento de Recolección',
                    style: TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.bold,
                      color: Color(0xFF0F172A),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              const Text(
                'Consulta el estado de tu denuncia y el recorrido del camión recolector.',
                style: TextStyle(fontSize: 13, color: Color(0xFF64748B)),
              ),
              const SizedBox(height: 18),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _ticketController,
                      textCapitalization: TextCapitalization.characters,
                      decoration: InputDecoration(
                        hintText: 'Ej. ECO-20261013-A6C2',
                        prefixIcon: const Icon(Icons.confirmation_number_outlined),
                        border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
                        filled: true,
                        fillColor: Colors.white,
                      ),
                      onSubmitted: (_) => _buscarTicket(),
                    ),
                  ),
                  const SizedBox(width: 10),
                  ElevatedButton(
                    onPressed: _buscando ? null : _buscarTicket,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: const Color(0xFF064E3B),
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                    ),
                    child: _buscando
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                          )
                        : const Icon(Icons.search_rounded),
                  ),
                ],
              ),
              const SizedBox(height: 24),
              if (_errorMensaje != null)
                Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: Colors.red.shade50,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(color: Colors.red.shade200),
                  ),
                  child: Row(
                    children: [
                      Icon(Icons.error_outline, color: Colors.red.shade700),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(_errorMensaje!, style: TextStyle(color: Colors.red.shade900, fontSize: 13)),
                      ),
                    ],
                  ),
                ),
              if (_denunciaEncontrada != null) ...[
                Card(
                  elevation: 2,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
                  color: Colors.white,
                  child: Padding(
                    padding: const EdgeInsets.all(20),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Text(
                              _denunciaEncontrada!['ticket'] ?? 'Sin ticket',
                              style: const TextStyle(
                                fontSize: 18,
                                fontWeight: FontWeight.bold,
                                color: Color(0xFF0F172A),
                              ),
                            ),
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                              decoration: BoxDecoration(
                                color: _obtenerColorEstado(_denunciaEncontrada!['estado']).withValues(alpha: 0.12),
                                borderRadius: BorderRadius.circular(8),
                                border: Border.all(color: _obtenerColorEstado(_denunciaEncontrada!['estado'])),
                              ),
                              child: Text(
                                (_denunciaEncontrada!['estado'] ?? 'DESCONOCIDO').toString().toUpperCase(),
                                style: TextStyle(
                                  color: _obtenerColorEstado(_denunciaEncontrada!['estado']),
                                  fontWeight: FontWeight.bold,
                                  fontSize: 12,
                                ),
                              ),
                            ),
                          ],
                        ),
                        const Divider(height: 24),
                        _itemDetalle(Icons.category_outlined, 'Categoría', _denunciaEncontrada!['categoria']),
                        _itemDetalle(Icons.location_on_outlined, 'Barrio', _denunciaEncontrada!['barrio']),
                        _itemDetalle(Icons.signpost_outlined, 'Dirección', _denunciaEncontrada!['direccion_referencia']),
                        _itemDetalle(Icons.description_outlined, 'Descripción', _denunciaEncontrada!['descripcion']),
                        if (_denunciaEncontrada!['observacion_cierre'] != null)
                          _itemDetalle(Icons.info_outline, 'Observación Operativa', _denunciaEncontrada!['observacion_cierre']),
                      ],
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _itemDetalle(IconData icon, String titulo, dynamic valor) {
    if (valor == null || valor.toString().isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 18, color: const Color(0xFF10B981)),
          const SizedBox(width: 8),
          Expanded(
            child: RichText(
              text: TextSpan(
                style: const TextStyle(fontSize: 13, color: Color(0xFF334155)),
                children: [
                  TextSpan(text: '$titulo: ', style: const TextStyle(fontWeight: FontWeight.bold)),
                  TextSpan(text: valor.toString()),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
