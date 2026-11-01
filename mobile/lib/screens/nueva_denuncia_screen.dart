import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:geolocator/geolocator.dart';
import 'package:http/http.dart' as http;
import 'package:image_picker/image_picker.dart';
import 'package:latlong2/latlong.dart';
import '../services/app_config.dart';
import '../services/auth_service.dart';

class NuevaDenunciaScreen extends StatefulWidget {
  const NuevaDenunciaScreen({super.key});

  @override
  State<NuevaDenunciaScreen> createState() => _NuevaDenunciaScreenState();
}

class _NuevaDenunciaScreenState extends State<NuevaDenunciaScreen> {
  File? _imagenEvidencia;
  Position? _posicionGps;
  DateTime? _fechaCaptura;

  String _categoriaSeleccionada = 'VERTEDERO_CLANDESTINO';
  final TextEditingController _descripcionController = TextEditingController();
  final TextEditingController _barrioController = TextEditingController(text: 'Sajonia');
  final TextEditingController _direccionController = TextEditingController();

  bool _obteniendoFoto = false;
  bool _obteniendoGps = false;
  bool _enviandoReporte = false;

  final MapController _mapController = MapController();
  final ImagePicker _picker = ImagePicker();

  final List<Map<String, dynamic>> _categorias = [
    {
      'id': 'VERTEDERO_CLANDESTINO',
      'nombre': 'Vertedero Clandestino / Basural',
      'icono': Icons.delete_forever_rounded,
      'color': Colors.brown.shade700,
    },
    {
      'id': 'CONTENEDOR_LLENO',
      'nombre': 'Contenedor Lleno / Desbordado',
      'icono': Icons.delete_sweep_rounded,
      'color': Colors.orange.shade800,
    },
    {
      'id': 'RESIDUO_VOLUMINOSO',
      'nombre': 'Residuo Voluminoso (Muebles/Chatarra)',
      'icono': Icons.weekend_rounded,
      'color': Colors.blueGrey.shade700,
    },
    {
      'id': 'RESTOS_PODA',
      'nombre': 'Restos de Poda / Ramas',
      'icono': Icons.park_rounded,
      'color': Colors.green.shade800,
    },
    {
      'id': 'ANIMAL_MUERTO',
      'nombre': 'Animal Muerto en Vía Pública',
      'icono': Icons.warning_amber_rounded,
      'color': Colors.red.shade800,
    },
  ];

  @override
  void initState() {
    super.initState();
    // Iniciar con geolocalización previa de cortesía
    _obtenerCoordenadasGps(silencioso: true);
  }

  @override
  void dispose() {
    _descripcionController.dispose();
    _barrioController.dispose();
    _direccionController.dispose();
    super.dispose();
  }

  // =========================================================================
  // FOTO + GPS SIMULTÁNEO
  // =========================================================================
  Future<void> _tomarFotoConCamara() async {
    try {
      setState(() => _obteniendoFoto = true);

      final XFile? foto = await _picker.pickImage(
        source: ImageSource.camera,
        imageQuality: 85,
        maxWidth: 1920,
        maxHeight: 1080,
      );

      if (foto == null) {
        setState(() => _obteniendoFoto = false);
        return;
      }

      setState(() {
        _imagenEvidencia = File(foto.path);
        _fechaCaptura = DateTime.now();
        _obteniendoFoto = false;
      });

      // Obtener de inmediato las coordenadas GPS exactas al momento de tomar la foto
      await _obtenerCoordenadasGps();
    } catch (e) {
      setState(() => _obteniendoFoto = false);
      _mostrarSnackBar('Error al acceder a la cámara: $e', esError: true);
    }
  }

  Future<void> _elegirDeGaleria() async {
    try {
      setState(() => _obteniendoFoto = true);

      final XFile? foto = await _picker.pickImage(
        source: ImageSource.gallery,
        imageQuality: 85,
      );

      if (foto == null) {
        setState(() => _obteniendoFoto = false);
        return;
      }

      setState(() {
        _imagenEvidencia = File(foto.path);
        _fechaCaptura = DateTime.now();
        _obteniendoFoto = false;
      });

      await _obtenerCoordenadasGps();
    } catch (e) {
      setState(() => _obteniendoFoto = false);
      _mostrarSnackBar('Error al abrir galería: $e', esError: true);
    }
  }

  Future<void> _obtenerCoordenadasGps({bool silencioso = false}) async {
    setState(() => _obteniendoGps = true);

    try {
      bool servicioHabilitado = await Geolocator.isLocationServiceEnabled();
      if (!servicioHabilitado) {
        if (!silencioso) {
          throw Exception('El GPS de tu celular está apagado. Por favor actívalo.');
        }
        return;
      }

      LocationPermission permiso = await Geolocator.checkPermission();
      if (permiso == LocationPermission.denied) {
        permiso = await Geolocator.requestPermission();
        if (permiso == LocationPermission.denied) {
          if (!silencioso) throw Exception('Permiso de ubicación denegado.');
          return;
        }
      }

      if (permiso == LocationPermission.deniedForever) {
        if (!silencioso) {
          throw Exception('Permisos de GPS denegados permanentemente en el celular.');
        }
        return;
      }

      final Position pos = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.high,
          timeLimit: Duration(seconds: 12),
        ),
      );

      setState(() {
        _posicionGps = pos;
      });

      WidgetsBinding.instance.addPostFrameCallback((_) {
        try {
          _mapController.move(LatLng(pos.latitude, pos.longitude), 16.0);
        } catch (_) {}
      });

      if (!silencioso) {
        _mostrarSnackBar('📍 Coordenadas GPS fijadas con exactitud.');
      }
    } catch (e) {
      if (!silencioso) {
        _mostrarSnackBar(e.toString(), esError: true);
      }
    } finally {
      setState(() => _obteniendoGps = false);
    }
  }

  // =========================================================================
  // ENVÍO AL AGENTE IA DE VALIDACIÓN Y ANTI-SPAM
  // =========================================================================
  Future<void> _enviarAlAgente() async {
    if (_imagenEvidencia == null) {
      _mostrarSnackBar('Toma una foto de la acumulación de residuos', esError: true);
      return;
    }

    if (_posicionGps == null) {
      _mostrarSnackBar('Captura la ubicación GPS antes de enviar', esError: true);
      return;
    }

    final descripcion = _descripcionController.text.trim();
    final barrio = _barrioController.text.trim();
    final direccion = _direccionController.text.trim();

    if (descripcion.isEmpty || barrio.isEmpty) {
      _mostrarSnackBar('Completa el barrio y la descripción de los residuos', esError: true);
      return;
    }

    setState(() => _enviandoReporte = true);

    try {
      final uri = Uri.parse(AppConfig.validarYEnviarUrl);
      final request = http.MultipartRequest('POST', uri);

      // Campos de la denuncia
      final auth = AuthService();
      request.fields['nombre_ciudadano'] = auth.nombre ?? 'Ciudadano';
      request.fields['telefono'] = '0981-000000';
      request.fields['categoria'] = _categoriaSeleccionada;
      request.fields['barrio'] = barrio;
      request.fields['direccion_referencia'] = direccion;
      request.fields['descripcion'] = descripcion;
      request.fields['latitud'] = _posicionGps!.latitude.toString();
      request.fields['longitud'] = _posicionGps!.longitude.toString();

      // Archivo de imagen real tomado con la cámara
      final multipartFile = await http.MultipartFile.fromPath(
        'imagenEvidencia',
        _imagenEvidencia!.path,
      );
      request.files.add(multipartFile);

      final streamedResponse = await request.send().timeout(
        const Duration(seconds: 35),
        onTimeout: () => throw Exception('Tiempo de espera agotado al conectar con el Agente EcoRuta.'),
      );

      final response = await http.Response.fromStream(streamedResponse);
      final Map<String, dynamic> data = jsonDecode(response.body);

      if (!mounted) return;

      _mostrarResultadoAgente(data);
    } catch (e) {
      if (!mounted) return;
      _mostrarSnackBar('Error al conectar con Agente EcoRuta: $e', esError: true);
    } finally {
      if (mounted) setState(() => _enviandoReporte = false);
    }
  }

  void _mostrarSnackBar(String msg, {bool esError = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(msg),
        backgroundColor: esError ? Colors.red.shade700 : const Color(0xFF10B981),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  void _mostrarResultadoAgente(Map<String, dynamic> data) {
    final veredicto = data['veredicto'] ?? 'DESCONOCIDO';
    final ticket = data['ticket'] ?? 'N/A';
    final observaciones = data['observaciones'] ?? '';
    final motivos = (data['motivos'] as List<dynamic>?) ?? [];
    final double confianza = ((data['confianza'] ?? 0.0) as num).toDouble();

    Color color;
    IconData icon;
    String titulo;

    if (veredicto == 'VALIDA') {
      color = const Color(0xFF10B981);
      icon = Icons.verified_rounded;
      titulo = 'Reporte Aprobado por Agente EcoRuta';
    } else if (veredicto == 'SOSPECHOSA') {
      color = Colors.amber.shade800;
      icon = Icons.warning_rounded;
      titulo = 'Reporte en Revisión por Agente';
    } else {
      color = Colors.red.shade700;
      icon = Icons.gpp_bad_rounded;
      titulo = 'Denuncia Descartada por Filtro Anti-Spam';
    }

    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        icon: Icon(icon, color: color, size: 52),
        title: Text(
          titulo,
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: color),
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (veredicto != 'RECHAZADA') ...[
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: color.withValues(alpha: 0.08),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: color.withValues(alpha: 0.3)),
                ),
                child: Column(
                  children: [
                    const Text('CÓDIGO DE TICKET', style: TextStyle(fontSize: 11, color: Colors.grey)),
                    const SizedBox(height: 2),
                    Text(
                      ticket,
                      style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900, color: color),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 12),
            ],
            Text(
              'Confianza IA: ${(confianza * 100).toStringAsFixed(0)}%',
              style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
            ),
            const SizedBox(height: 6),
            Text(observaciones, style: const TextStyle(fontSize: 13)),
            if (motivos.isNotEmpty) ...[
              const SizedBox(height: 8),
              const Text('Motivos detallados:', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 12)),
              ...motivos.map(
                (m) => Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Text('• $m', style: const TextStyle(fontSize: 12, color: Colors.red)),
                ),
              ),
            ],
          ],
        ),
        actions: [
          ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor: color,
              foregroundColor: Colors.white,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
            ),
            onPressed: () {
              Navigator.pop(ctx);
              if (veredicto == 'VALIDA') {
                setState(() {
                  _imagenEvidencia = null;
                  _descripcionController.clear();
                  _direccionController.clear();
                });
              }
            },
            child: const Text('Entendido'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final latDefecto = _posicionGps?.latitude ?? -25.2960;
    final lonDefecto = _posicionGps?.longitude ?? -57.6360;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Banner Agente
          Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: const Color(0xFFECFDF5),
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: const Color(0xFFA7F3D0)),
            ),
            child: const Row(
              children: [
                Icon(Icons.shield_outlined, color: Color(0xFF10B981), size: 28),
                SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'Escudo EcoRuta: Cada reporte es validado en tiempo real con IA antispam y georreferenciación.',
                    style: TextStyle(fontSize: 12, color: Color(0xFF065F46), fontWeight: FontWeight.w500),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 18),

          // SECCIÓN 1: FOTOGRAFÍA
          const Text(
            '1. Fotografía de Evidencia',
            style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Color(0xFF0F172A)),
          ),
          const SizedBox(height: 8),
          Container(
            height: 200,
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: Colors.grey.shade300),
            ),
            clipBehavior: Clip.antiAlias,
            child: _imagenEvidencia != null
                ? Stack(
                    fit: StackFit.expand,
                    children: [
                      Image.file(_imagenEvidencia!, fit: BoxFit.cover),
                      Positioned(
                        bottom: 8,
                        left: 8,
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                          decoration: BoxDecoration(
                            color: Colors.black.withValues(alpha: 0.6),
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(
                            '📸 Captura: ${_fechaCaptura?.hour.toString().padLeft(2, '0')}:${_fechaCaptura?.minute.toString().padLeft(2, '0')}',
                            style: const TextStyle(color: Colors.white, fontSize: 11),
                          ),
                        ),
                      ),
                      Positioned(
                        top: 8,
                        right: 8,
                        child: CircleAvatar(
                          backgroundColor: Colors.black54,
                          child: IconButton(
                            icon: const Icon(Icons.refresh, color: Colors.white),
                            onPressed: _tomarFotoConCamara,
                          ),
                        ),
                      ),
                    ],
                  )
                : Center(
                    child: _obteniendoFoto
                        ? const CircularProgressIndicator(color: Color(0xFF10B981))
                        : Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              const Icon(Icons.camera_alt_outlined, size: 48, color: Colors.grey),
                              const SizedBox(height: 8),
                              const Text('Toma una foto clara del foco de basura', style: TextStyle(color: Colors.grey, fontSize: 13)),
                              const SizedBox(height: 12),
                              Row(
                                mainAxisAlignment: MainAxisAlignment.center,
                                children: [
                                  ElevatedButton.icon(
                                    style: ElevatedButton.styleFrom(
                                      backgroundColor: const Color(0xFF10B981),
                                      foregroundColor: Colors.white,
                                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                                    ),
                                    onPressed: _tomarFotoConCamara,
                                    icon: const Icon(Icons.camera_alt),
                                    label: const Text('Cámara'),
                                  ),
                                  const SizedBox(width: 8),
                                  OutlinedButton.icon(
                                    style: OutlinedButton.styleFrom(
                                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                                    ),
                                    onPressed: _elegirDeGaleria,
                                    icon: const Icon(Icons.photo_library),
                                    label: const Text('Galería'),
                                  ),
                                ],
                              ),
                            ],
                          ),
                  ),
          ),
          const SizedBox(height: 20),

          // SECCIÓN 2: UBICACIÓN GPS
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text(
                '2. Ubicación GPS Exacta',
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Color(0xFF0F172A)),
              ),
              TextButton.icon(
                onPressed: _obteniendoGps ? null : () => _obtenerCoordenadasGps(),
                icon: _obteniendoGps
                    ? const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.my_location, size: 16),
                label: const Text('Actualizar GPS', style: TextStyle(fontSize: 12)),
              ),
            ],
          ),
          Container(
            height: 180,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: Colors.grey.shade300),
            ),
            clipBehavior: Clip.antiAlias,
            child: FlutterMap(
              mapController: _mapController,
              options: MapOptions(
                initialCenter: LatLng(latDefecto, lonDefecto),
                initialZoom: 15.0,
                onTap: (_, latlng) {
                  setState(() {
                    _posicionGps = Position(
                      latitude: latlng.latitude,
                      longitude: latlng.longitude,
                      timestamp: DateTime.now(),
                      accuracy: 1.0,
                      altitude: 0.0,
                      heading: 0.0,
                      speed: 0.0,
                      speedAccuracy: 0.0,
                      altitudeAccuracy: 0.0,
                      headingAccuracy: 0.0,
                    );
                  });
                },
              ),
              children: [
                TileLayer(
                  urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
                  userAgentPackageName: 'com.ecoruta.app',
                ),
                MarkerLayer(
                  markers: [
                    Marker(
                      point: LatLng(latDefecto, lonDefecto),
                      width: 44,
                      height: 44,
                      child: const Icon(
                        Icons.location_on_rounded,
                        color: Colors.red,
                        size: 40,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(
              _posicionGps != null
                  ? '📍 Lat: ${_posicionGps!.latitude.toStringAsFixed(5)}, Lon: ${_posicionGps!.longitude.toStringAsFixed(5)} (Toca el mapa para ajustar)'
                  : '⚠️ Esperando GPS... o toca el mapa para fijar punto.',
              style: TextStyle(fontSize: 11, color: Colors.grey.shade700),
            ),
          ),
          const SizedBox(height: 20),

          // SECCIÓN 3: CATEGORÍA Y DETALLES
          const Text(
            '3. Tipo de Residuo',
            style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Color(0xFF0F172A)),
          ),
          const SizedBox(height: 8),
          DropdownButtonFormField<String>(
            initialValue: _categoriaSeleccionada,
            decoration: InputDecoration(
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              filled: true,
              fillColor: Colors.white,
            ),
            items: _categorias.map((cat) {
              return DropdownMenuItem<String>(
                value: cat['id'],
                child: Row(
                  children: [
                    Icon(cat['icono'], color: cat['color'], size: 20),
                    const SizedBox(width: 8),
                    Text(cat['nombre'], style: const TextStyle(fontSize: 13)),
                  ],
                ),
              );
            }).toList(),
            onChanged: (val) {
              if (val != null) setState(() => _categoriaSeleccionada = val);
            },
          ),
          const SizedBox(height: 14),

          TextField(
            controller: _barrioController,
            decoration: InputDecoration(
              labelText: 'Barrio de Asunción',
              prefixIcon: const Icon(Icons.location_city_outlined),
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              filled: true,
              fillColor: Colors.white,
            ),
          ),
          const SizedBox(height: 14),

          TextField(
            controller: _direccionController,
            decoration: InputDecoration(
              labelText: 'Dirección o Referencia',
              hintText: 'Ej. Calle Palma c/ Colón, frente a la plaza',
              prefixIcon: const Icon(Icons.signpost_outlined),
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              filled: true,
              fillColor: Colors.white,
            ),
          ),
          const SizedBox(height: 14),

          TextField(
            controller: _descripcionController,
            maxLines: 3,
            decoration: InputDecoration(
              labelText: 'Descripción del foco de residuos',
              hintText: 'Describe la cantidad de basura acumulada, bolsas, ramas...',
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              filled: true,
              fillColor: Colors.white,
            ),
          ),
          const SizedBox(height: 24),

          // BOTÓN ENVIAR
          ElevatedButton(
            onPressed: _enviandoReporte ? null : _enviarAlAgente,
            style: ElevatedButton.styleFrom(
              backgroundColor: const Color(0xFF10B981),
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(vertical: 16),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              elevation: 2,
            ),
            child: _enviandoReporte
                ? const Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                      ),
                      SizedBox(width: 12),
                      Text('Agente IA Validando Denuncia...', style: TextStyle(fontWeight: FontWeight.bold)),
                    ],
                  )
                : const Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(Icons.send_rounded),
                      SizedBox(width: 8),
                      Text(
                        'Enviar a Verificación EcoRuta',
                        style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                      ),
                    ],
                  ),
          ),
          const SizedBox(height: 20),
        ],
      ),
    );
  }
}
